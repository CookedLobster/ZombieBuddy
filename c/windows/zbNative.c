#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <bcrypt.h>

HMODULE hOrig = NULL;

#define ZB_JAR "ZombieBuddy.jar"
#define AGENT_OPTIONS_MAX 2048
#define NT_SUCCESS(Status) (((NTSTATUS)(Status)) >= 0)

// Minimal DllMain to prevent CRT initialization
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    return TRUE;
}

int  (*pAgent_OnAttach)(void*, char*, void*) = NULL;
int  (*pAgent_OnLoad)(void*, char*, void*)   = NULL;
void (*pAgent_OnUnload)(void*)               = NULL;

void write_msg(const char* msg) {
    WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), msg, lstrlenA(msg), NULL, NULL);
}

void init_instrument_dll() {
    if (hOrig) return; // already loaded

    SetDllDirectoryA(".\\jre64\\bin");
    hOrig = LoadLibraryA("instrument.dll");
    SetDllDirectoryA(NULL);

    if (!hOrig) {
        write_msg("[zbNative] Failed to load instrument.dll\n");
        return;
    }

    *(void**)&pAgent_OnAttach = GetProcAddress(hOrig, "Agent_OnAttach");
    *(void**)&pAgent_OnLoad   = GetProcAddress(hOrig, "Agent_OnLoad");
    *(void**)&pAgent_OnUnload = GetProcAddress(hOrig, "Agent_OnUnload");
}

static BOOL read_text_file(const char* path, char* buf, DWORD cap, DWORD* outLen) {
    HANDLE f = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    DWORD read = 0;
    BOOL ok = FALSE;
    if (f == INVALID_HANDLE_VALUE) {
        return FALSE;
    }
    if (ReadFile(f, buf, cap - 1, &read, NULL) && read < cap) {
        buf[read] = '\0';
        *outLen = read;
        ok = TRUE;
    }
    CloseHandle(f);
    return ok;
}

static BOOL sha256_file(const char* path, BYTE digest[32]) {
    BCRYPT_ALG_HANDLE alg = NULL;
    BCRYPT_HASH_HANDLE hash = NULL;
    HANDLE f = INVALID_HANDLE_VALUE;
    PUCHAR object = NULL;
    PBYTE buf = NULL;
    DWORD objectLen = 0;
    DWORD cb = 0;
    DWORD read = 0;
    BOOL ok = FALSE;

    if (!NT_SUCCESS(BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, NULL, 0))) {
        goto done;
    }
    if (!NT_SUCCESS(BCryptGetProperty(alg, BCRYPT_OBJECT_LENGTH, (PUCHAR)&objectLen,
                                      sizeof(objectLen), &cb, 0))) {
        goto done;
    }
    object = (PUCHAR)LocalAlloc(LMEM_FIXED, objectLen);
    if (object == NULL) {
        goto done;
    }
    buf = (PBYTE)LocalAlloc(LMEM_FIXED, 8192);
    if (buf == NULL) {
        goto done;
    }
    if (!NT_SUCCESS(BCryptCreateHash(alg, &hash, object, objectLen, NULL, 0, 0))) {
        goto done;
    }

    f = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) {
        goto done;
    }
    for (;;) {
        if (!ReadFile(f, buf, 8192, &read, NULL)) {
            goto done;
        }
        if (read == 0) {
            break;
        }
        if (!NT_SUCCESS(BCryptHashData(hash, buf, read, 0))) {
            goto done;
        }
    }
    if (!NT_SUCCESS(BCryptFinishHash(hash, digest, 32, 0))) {
        goto done;
    }
    ok = TRUE;

done:
    if (f != INVALID_HANDLE_VALUE) {
        CloseHandle(f);
    }
    if (hash != NULL) {
        BCryptDestroyHash(hash);
    }
    if (object != NULL) {
        LocalFree(object);
    }
    if (buf != NULL) {
        LocalFree(buf);
    }
    if (alg != NULL) {
        BCryptCloseAlgorithmProvider(alg, 0);
    }
    return ok;
}

static int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static BOOL hash_text_matches(const char* text, DWORD len, const BYTE digest[32]) {
    DWORD i = 0;
    DWORD hexPos = 0;
    for (; i < len && hexPos < 64; i++) {
        char c = text[i];
        int nibble;
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            continue;
        }
        nibble = hex_nibble(c);
        if (nibble < 0) {
            return FALSE;
        }
        if ((hexPos & 1) == 0) {
            if ((digest[hexPos / 2] >> 4) != (BYTE)nibble) {
                return FALSE;
            }
        } else {
            if ((digest[hexPos / 2] & 0x0F) != (BYTE)nibble) {
                return FALSE;
            }
        }
        hexPos++;
    }
    if (hexPos != 64) {
        return FALSE;
    }
    for (; i < len; i++) {
        char c = text[i];
        if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
            return FALSE;
        }
    }
    return TRUE;
}

void check_and_apply_update(const char* jarPath) {
    char newJarPath[1024];
    char hashPath[1024];
    char expected[128];
    DWORD expectedLen = 0;
    BYTE digest[32];

    wsprintfA(newJarPath, "%s.new", jarPath);
    wsprintfA(hashPath, "%s.sha256", newJarPath);

    // Check if .new file exists
    DWORD attrs = GetFileAttributesA(newJarPath);
    if (attrs == INVALID_FILE_ATTRIBUTES || (attrs & FILE_ATTRIBUTE_DIRECTORY)) {
        return; // No update pending
    }

    // Only apply a .new file that matches the SHA-256 recorded by the Java
    // updater after it verified the JAR signature. This rejects stale,
    // truncated, or accidentally substituted files.
    if (!read_text_file(hashPath, expected, sizeof(expected), &expectedLen)) {
        write_msg("[zbNative] Refusing pending update: missing .new.sha256\n");
        return;
    }
    if (!sha256_file(newJarPath, digest)) {
        write_msg("[zbNative] Refusing pending update: cannot hash .new file\n");
        return;
    }
    if (!hash_text_matches(expected, expectedLen, digest)) {
        write_msg("[zbNative] Refusing pending update: SHA-256 mismatch\n");
        return;
    }

    // Update is pending and verified - apply it
    write_msg("[zbNative] Pending update verified, applying...\n");

    // Rename .new file to JAR file, replacing existing file if it exists
    if (MoveFileExA(newJarPath, jarPath, MOVEFILE_REPLACE_EXISTING)) {
        DeleteFileA(hashPath);
        write_msg("[zbNative] Successfully applied update\n");
    } else {
        write_msg("[zbNative] Error: Failed to apply update\n");
    }
}

int build_agent_options(const char* tail, char* out, int outSize) {
    int jarLen = lstrlenA(ZB_JAR);
    int tailLen = tail == NULL ? 0 : lstrlenA(tail);
    int needsArgs = tailLen > 0;
    int totalLen = jarLen + (needsArgs ? 1 + tailLen : 0);

    if (totalLen + 1 > outSize) {
        write_msg("[zbNative] Error: agent options are too long\n");
        return 0;
    }

    lstrcpyA(out, ZB_JAR);
    if (needsArgs) {
        out[jarLen] = '=';
        lstrcpyA(out + jarLen + 1, tail);
    }
    return 1;
}

__declspec(dllexport) int Agent_OnLoad(void* jvm, char* tail, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnLoad) {
        return -1;
    }

    // Check for pending update before loading the agent
    check_and_apply_update(ZB_JAR);

    char agentOptions[AGENT_OPTIONS_MAX];
    if (!build_agent_options(tail, agentOptions, sizeof(agentOptions))) {
        return -1;
    }

    return pAgent_OnLoad(jvm, agentOptions, reserved);
}

__declspec(dllexport) int Agent_OnAttach(void* jvm, char* args, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnAttach) {
        return -1;
    }

    char agentOptions[AGENT_OPTIONS_MAX];
    if (!build_agent_options(args, agentOptions, sizeof(agentOptions))) {
        return -1;
    }

    return pAgent_OnAttach(jvm, agentOptions, reserved);
}

__declspec(dllexport) void Agent_OnUnload(void* jvm) {
    if (hOrig == NULL) {
        return;
    }
    if (pAgent_OnUnload) {
        pAgent_OnUnload(jvm);
    }

    FreeLibrary(hOrig);
    hOrig = NULL;
}

