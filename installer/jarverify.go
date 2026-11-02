package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"crypto/sha512"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"sort"
	"strings"
	"time"

	"go.mozilla.org/pkcs7"
)

// This file verifies a JAR the way jarsigner -verify does: the JAR's
// META-INF/*.SF signature file must be signed by the pinned certificate, the
// manifest digests must match, and every non-signature entry must be covered
// by the signed manifest. Checking only for the presence of a certificate in a
// META-INF/*.RSA block is not enough: an attacker can copy a valid signature
// block from a different JAR and pair it with a modified manifest and entries.

const (
	maxSignatureMetadataBytes = 32 << 20 // 32 MiB
	sha256DigestName          = "sha-256"
	sha384DigestName          = "sha-384"
	sha512DigestName          = "sha-512"
)

var supportedDigests = map[string]func() hash.Hash{
	sha256DigestName: sha256.New,
	sha384DigestName: sha512.New384,
	sha512DigestName: sha512.New,
}

type jarManifest struct {
	raw      []byte
	main     map[string]string
	mainRaw  []byte
	sections map[string]manifestSection
}

type manifestSection struct {
	attrs map[string]string
	raw   []byte
}

// verifyJarSignatureAndManifest verifies the JAR signature, the manifest
// digest, and every entry digest. fingerprintHex is the SHA-256 of the signer
// certificate's DER encoding, in lowercase or uppercase hex.
func verifyJarSignatureAndManifest(path string, fingerprintHex string) error {
	fingerprint, err := hex.DecodeString(strings.TrimSpace(fingerprintHex))
	if err != nil || len(fingerprint) != sha256.Size {
		return fmt.Errorf("invalid pinned certificate fingerprint")
	}

	zr, err := zip.OpenReader(path)
	if err != nil {
		return fmt.Errorf("opening JAR: %w", err)
	}
	defer zr.Close()

	entries, err := indexJarEntries(zr.File)
	if err != nil {
		return err
	}

	manifestFile, ok := entries["META-INF/MANIFEST.MF"]
	if !ok {
		return fmt.Errorf("JAR is not signed (missing META-INF/MANIFEST.MF)")
	}
	manifestRaw, err := readZipEntryLimited(manifestFile, maxSignatureMetadataBytes)
	if err != nil {
		return fmt.Errorf("reading manifest: %w", err)
	}
	manifest, err := parseJarManifest(manifestRaw)
	if err != nil {
		return err
	}

	signatureFiles := 0
	for name, entry := range entries {
		if !isJarSignatureFile(name) {
			continue
		}
		signatureFiles++

		signatureRaw, err := readZipEntryLimited(entry, maxSignatureMetadataBytes)
		if err != nil {
			return fmt.Errorf("reading %s: %w", name, err)
		}
		block, err := matchingSignatureBlock(entries, name)
		if err != nil {
			return err
		}
		blockRaw, err := readZipEntryLimited(block, maxSignatureMetadataBytes)
		if err != nil {
			return fmt.Errorf("reading %s: %w", block.Name, err)
		}
		if err := verifySignatureBlock(blockRaw, signatureRaw, fingerprint); err != nil {
			return fmt.Errorf("%s: %w", block.Name, err)
		}
		if err := verifySignedManifest(manifest, signatureRaw); err != nil {
			return fmt.Errorf("%s: %w", name, err)
		}
	}

	if signatureFiles == 0 {
		return fmt.Errorf("JAR is not signed (no META-INF/*.SF signature file)")
	}

	return verifyManifestEntries(entries, manifest)
}

func indexJarEntries(files []*zip.File) (map[string]*zip.File, error) {
	entries := make(map[string]*zip.File, len(files))
	for _, f := range files {
		if f.FileInfo().IsDir() {
			continue
		}
		name := f.Name
		if name == "" || strings.HasPrefix(name, "/") || strings.HasPrefix(name, `\`) ||
			strings.Contains(name, `\`) || hasParentPathSegment(name) {
			return nil, fmt.Errorf("JAR contains an unsafe entry name: %q", name)
		}
		if _, exists := entries[name]; exists {
			return nil, fmt.Errorf("JAR contains duplicate entry %q", name)
		}
		entries[name] = f
	}
	return entries, nil
}

func hasParentPathSegment(name string) bool {
	for _, part := range strings.Split(name, "/") {
		if part == ".." {
			return true
		}
	}
	return false
}

func isJarSignatureFile(name string) bool {
	upper := strings.ToUpper(name)
	return strings.HasPrefix(upper, "META-INF/") && strings.HasSuffix(upper, ".SF")
}

func isJarSignatureBlock(name string) bool {
	upper := strings.ToUpper(name)
	return strings.HasPrefix(upper, "META-INF/") &&
		(strings.HasSuffix(upper, ".RSA") || strings.HasSuffix(upper, ".DSA") || strings.HasSuffix(upper, ".EC"))
}

// isSignatureMetadata reports whether an entry is allowed to be unsigned
// because it is part of the JAR signing machinery. META-INF/services and
// META-INF/versions entries are intentionally not exempt: those can change
// application behaviour and must be covered by the signature.
func isSignatureMetadata(name string) bool {
	if name == "META-INF/MANIFEST.MF" {
		return true
	}
	return isJarSignatureFile(name) || isJarSignatureBlock(name)
}

func matchingSignatureBlock(entries map[string]*zip.File, signatureName string) (*zip.File, error) {
	base := signatureName[:len(signatureName)-len(".SF")]
	var found *zip.File
	for name, entry := range entries {
		if !isJarSignatureBlock(name) {
			continue
		}
		blockBase := name
		for _, extension := range []string{".RSA", ".DSA", ".EC"} {
			if strings.HasSuffix(strings.ToUpper(name), extension) {
				blockBase = name[:len(name)-len(extension)]
				break
			}
		}
		if strings.EqualFold(blockBase, base) {
			if found != nil {
				return nil, fmt.Errorf("multiple signature blocks for %s", signatureName)
			}
			found = entry
		}
	}
	if found == nil {
		return nil, fmt.Errorf("missing signature block for %s", signatureName)
	}
	return found, nil
}

func verifySignatureBlock(blockRaw, signatureRaw, fingerprint []byte) error {
	p7, err := pkcs7.Parse(blockRaw)
	if err != nil {
		return fmt.Errorf("parsing signature block: %w", err)
	}
	// A JAR signature is detached: the signed content is the .SF file.
	p7.Content = signatureRaw
	if err := p7.Verify(); err != nil {
		return fmt.Errorf("signature verification failed: %w", err)
	}

	signer := p7.GetOnlySigner()
	if signer == nil {
		return errors.New("expected exactly one signer certificate")
	}
	sum := sha256.Sum256(signer.Raw)
	if !bytes.Equal(sum[:], fingerprint) {
		return errors.New("signature block is not signed by the pinned ZombieBuddy certificate")
	}
	now := time.Now()
	if now.Before(signer.NotBefore) || now.After(signer.NotAfter) {
		return errors.New("signer certificate is not currently valid")
	}
	if signer.KeyUsage != 0 && signer.KeyUsage&x509.KeyUsageDigitalSignature == 0 {
		return errors.New("signer certificate does not allow digital signatures")
	}
	return nil
}

func parseJarManifest(raw []byte) (*jarManifest, error) {
	sections, err := splitManifestSections(raw)
	if err != nil {
		return nil, fmt.Errorf("parsing manifest: %w", err)
	}
	if len(sections) == 0 {
		return nil, errors.New("manifest is empty")
	}

	main, err := parseManifestAttributes(sections[0])
	if err != nil {
		return nil, fmt.Errorf("parsing manifest main attributes: %w", err)
	}
	manifest := &jarManifest{
		raw:      raw,
		main:     main,
		mainRaw:  sections[0],
		sections: make(map[string]manifestSection, len(sections)-1),
	}
	for _, sectionRaw := range sections[1:] {
		attrs, err := parseManifestAttributes(sectionRaw)
		if err != nil {
			return nil, fmt.Errorf("parsing manifest section: %w", err)
		}
		name, ok := attrs["name"]
		if !ok || strings.TrimSpace(name) == "" {
			return nil, errors.New("manifest section is missing Name")
		}
		name = strings.TrimSpace(name)
		if _, exists := manifest.sections[name]; exists {
			return nil, fmt.Errorf("duplicate manifest section for %q", name)
		}
		manifest.sections[name] = manifestSection{attrs: attrs, raw: sectionRaw}
	}
	return manifest, nil
}

func verifySignedManifest(manifest *jarManifest, signatureRaw []byte) error {
	sections, err := splitManifestSections(signatureRaw)
	if err != nil {
		return fmt.Errorf("parsing signature file: %w", err)
	}
	if len(sections) == 0 {
		return errors.New("signature file is empty")
	}
	signatureAttrs, err := parseManifestAttributes(sections[0])
	if err != nil {
		return fmt.Errorf("parsing signature file main attributes: %w", err)
	}

	algorithms, err := digestAlgorithms(signatureAttrs)
	if err != nil {
		return err
	}
	// Verify every supported algorithm present, so a malformed weaker digest
	// cannot hide behind a valid stronger one.
	for _, algorithm := range algorithms {
		expected, err := digestAttribute(signatureAttrs, algorithm, "digest-manifest")
		if err != nil {
			return err
		}
		if !bytes.Equal(expected, digestBytes(algorithm, manifest.raw)) {
			return fmt.Errorf("manifest digest mismatch (%s)", algorithm)
		}
	}
	algorithm := strongestDigest(algorithms)

	expectedMain, err := digestAttribute(signatureAttrs, algorithm, "digest-manifest-main-attributes")
	if err != nil {
		return err
	}
	if !bytes.Equal(expectedMain, digestBytes(algorithm, manifest.mainRaw)) {
		return fmt.Errorf("manifest main-attributes digest mismatch (%s)", algorithm)
	}

	signatureSections := make(map[string]manifestSection)
	for _, sectionRaw := range sections[1:] {
		attrs, err := parseManifestAttributes(sectionRaw)
		if err != nil {
			return fmt.Errorf("parsing signature file section: %w", err)
		}
		name, ok := attrs["name"]
		if !ok || strings.TrimSpace(name) == "" {
			return errors.New("signature file section is missing Name")
		}
		name = strings.TrimSpace(name)
		if _, exists := signatureSections[name]; exists {
			return fmt.Errorf("duplicate signature file section for %q", name)
		}
		signatureSections[name] = manifestSection{attrs: attrs, raw: sectionRaw}
	}

	for name, section := range signatureSections {
		manifestSection, ok := manifest.sections[name]
		if !ok {
			return fmt.Errorf("signature file contains a section for an unknown entry %q", name)
		}
		expected, err := digestAttribute(section.attrs, algorithm, "digest")
		if err != nil {
			return fmt.Errorf("entry %q: %w", name, err)
		}
		if !bytes.Equal(expected, digestBytes(algorithm, manifestSection.raw)) {
			return fmt.Errorf("signature file digest mismatch for entry %q", name)
		}
	}

	for name, section := range manifest.sections {
		if isSignatureMetadata(name) {
			continue
		}
		if _, ok := signatureSections[name]; !ok {
			return fmt.Errorf("manifest section %q is not covered by a signature file", name)
		}
		if _, err := digestAttribute(section.attrs, algorithm, "digest"); err != nil {
			return fmt.Errorf("manifest entry %q: %w", name, err)
		}
	}
	return nil
}

func verifyManifestEntries(entries map[string]*zip.File, manifest *jarManifest) error {
	algorithms := map[string]struct{}{}
	for _, section := range manifest.sections {
		for key := range section.attrs {
			if strings.HasSuffix(key, "-digest") {
				algorithm := strings.TrimSuffix(key, "-digest")
				if _, ok := supportedDigests[algorithm]; ok {
					algorithms[algorithm] = struct{}{}
				}
			}
		}
	}
	if len(algorithms) == 0 {
		return errors.New("manifest contains no supported entry digests")
	}
	// The signature file selects the digest algorithm; verifyManifestEntries
	// only needs to know that each manifest digest is supported and verified.
	algorithm := strongestDigest(setToSlice(algorithms))

	for name := range manifest.sections {
		if isSignatureMetadata(name) {
			continue
		}
		if _, ok := entries[name]; !ok {
			return fmt.Errorf("manifest references missing entry %q", name)
		}
	}

	for name, entry := range entries {
		if isSignatureMetadata(name) {
			continue
		}
		section, ok := manifest.sections[name]
		if !ok {
			return fmt.Errorf("JAR entry %q is not listed in the signed manifest", name)
		}
		expected, err := digestAttribute(section.attrs, algorithm, "digest")
		if err != nil {
			return fmt.Errorf("manifest entry %q: %w", name, err)
		}
		actual, err := digestZipEntry(entry, algorithm)
		if err != nil {
			return fmt.Errorf("hashing %q: %w", name, err)
		}
		if !bytes.Equal(expected, actual) {
			return fmt.Errorf("entry digest mismatch for %q", name)
		}
	}
	return nil
}

func splitManifestSections(raw []byte) ([][]byte, error) {
	var sections [][]byte
	start := 0
	for i := 0; i < len(raw); {
		j := bytes.IndexByte(raw[i:], '\n')
		if j < 0 {
			j = len(raw)
		} else {
			j = i + j + 1
		}
		if isBlankManifestLine(raw[i:j]) {
			if j > start {
				sections = append(sections, raw[start:j])
			}
			start = j
		}
		i = j
	}
	if start < len(raw) {
		sections = append(sections, raw[start:])
	}
	return sections, nil
}

func isBlankManifestLine(line []byte) bool {
	return len(bytes.TrimRight(line, "\r\n")) == 0
}

func parseManifestAttributes(section []byte) (map[string]string, error) {
	attrs := make(map[string]string)
	var key string
	var value []byte

	flush := func() {
		if key != "" {
			attrs[key] = string(value)
			key = ""
			value = nil
		}
	}

	for _, rawLine := range bytes.Split(section, []byte("\n")) {
		line := bytes.TrimSuffix(rawLine, []byte("\r"))
		if len(line) == 0 {
			continue
		}
		if line[0] == ' ' {
			if key == "" {
				return nil, errors.New("attribute continuation without a preceding attribute")
			}
			value = append(value, line[1:]...)
			continue
		}
		flush()
		colon := bytes.IndexByte(line, ':')
		if colon <= 0 {
			return nil, fmt.Errorf("malformed attribute line %q", string(line))
		}
		key = strings.ToLower(strings.TrimSpace(string(line[:colon])))
		if _, exists := attrs[key]; exists {
			return nil, fmt.Errorf("duplicate attribute %q", key)
		}
		value = append(value, bytes.TrimLeft(line[colon+1:], " ")...)
	}
	flush()
	return attrs, nil
}

func digestAlgorithms(attrs map[string]string) ([]string, error) {
	var algorithms []string
	for key := range attrs {
		if !strings.HasSuffix(key, "-digest-manifest") {
			continue
		}
		algorithm := strings.TrimSuffix(key, "-digest-manifest")
		if _, ok := supportedDigests[algorithm]; ok {
			algorithms = append(algorithms, algorithm)
		}
	}
	if len(algorithms) == 0 {
		return nil, errors.New("signature file contains no supported SHA-2 manifest digest")
	}
	return algorithms, nil
}

func strongestDigest(algorithms []string) string {
	rank := map[string]int{sha512DigestName: 3, sha384DigestName: 2, sha256DigestName: 1}
	best := sha256DigestName
	bestRank := 0
	for _, algorithm := range algorithms {
		if rank[algorithm] > bestRank {
			best = algorithm
			bestRank = rank[algorithm]
		}
	}
	return best
}

func setToSlice(values map[string]struct{}) []string {
	out := make([]string, 0, len(values))
	for value := range values {
		out = append(out, value)
	}
	sort.Strings(out)
	return out
}

func digestAttribute(attrs map[string]string, algorithm, suffix string) ([]byte, error) {
	key := algorithm + "-" + suffix
	value, ok := attrs[key]
	if !ok {
		return nil, fmt.Errorf("missing %s", key)
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(value))
	if err != nil {
		return nil, fmt.Errorf("invalid base64 in %s: %w", key, err)
	}
	if len(decoded) != supportedDigests[algorithm]().Size() {
		return nil, fmt.Errorf("invalid digest length in %s", key)
	}
	return decoded, nil
}

func digestBytes(algorithm string, data []byte) []byte {
	h := supportedDigests[algorithm]()
	_, _ = h.Write(data)
	return h.Sum(nil)
}

func digestZipEntry(entry *zip.File, algorithm string) ([]byte, error) {
	reader, err := entry.Open()
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	h := supportedDigests[algorithm]()
	if _, err := io.Copy(h, reader); err != nil {
		return nil, err
	}
	return h.Sum(nil), nil
}

func readZipEntryLimited(entry *zip.File, limit int64) ([]byte, error) {
	reader, err := entry.Open()
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	return io.ReadAll(io.LimitReader(reader, limit))
}
