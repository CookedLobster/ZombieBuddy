def author_slug(name, id)
  slug = name.downcase.gsub(/[^[[:alnum:]]]+/, "-").gsub(/^-+|-+$/, "")
  slug.empty? ? id.to_s : slug
end

def valid_author_key?(key)
  key.is_a?(String) && key.match?(/\A[0-9a-fA-F]{64}\z/)
end

def load_author_files
  Dir["authors/*.json"].sort.map do |path|
    [path, JSON.parse(File.read(path))]
  end
end

def validate_author_entries(entries)
  seen_ids = {}
  seen_keys = {}
  entries.each do |path, author|
    raise "#{path}: expected a JSON object" unless author.is_a?(Hash)
    id = author["id"]
    name = author["name"]
    keys = author["keys"]
    raise "#{path}: id must be a non-negative integer" unless id.is_a?(Integer) && id >= 0
    raise "#{path}: name must be a non-empty string" unless name.is_a?(String) && !name.empty?
    raise "#{path}: keys must be a non-empty array" unless keys.is_a?(Array) && !keys.empty?

    if seen_ids.key?(id)
      raise "duplicate author id #{id} in #{seen_ids[id]} and #{path}"
    end
    seen_ids[id] = path

    keys.each do |key|
      raise "#{path}: key must be 64 hex characters: #{key.inspect}" unless valid_author_key?(key)
      normalized = key.downcase
      if seen_keys.key?(normalized)
        raise "duplicate signing key in #{seen_keys[normalized]} and #{path}"
      end
      seen_keys[normalized] = path
    end
  end
end

namespace :authors do
  desc "Add a new author to the database"
  task :add do
    _, id, name, key = ARGV
    msg = "Usage: rake authors:add <id> <name> <key>"
    raise msg unless id =~ /^\d+$/
    raise msg unless name.size > 0
    raise msg unless valid_author_key?(key)

    path = File.join("authors", "#{author_slug(name, id)}.json")
    raise "#{path} already exists" if File.exist?(path)

    entries = load_author_files
    numeric_id = id.to_i
    if entries.any? { |_, author| author["id"] == numeric_id }
      raise "author id #{numeric_id} already exists"
    end
    if entries.any? { |_, author| Array(author["keys"]).any? { |k| k.to_s.casecmp?(key) } }
      raise "signing key already belongs to another author"
    end

    File.write(path, JSON.pretty_generate("id" => numeric_id, "name" => name, "keys" => [key]))

    Rake::Task["authors:sign"].invoke
    exit 0
  end

  desc 'aggregate authors/*.json into authors.json and sign it'
  task :sign do
    entries = load_author_files
    validate_author_entries(entries)

    authors = entries.map { |_, author| author }
    authors = authors.sort_by { |a| a['name'].downcase }
    authors = authors.map { |a| { "name" => a['name'], "id" => a['id'], "keys" => a['keys'] } }

    data = JSON.parse(File.read("authors.json"))

    if authors == data['authors']
      puts "authors.json unchanged, skipping sign"
      next
    end

    data['updated_at'] = Time.now.utc.strftime("%Y-%m-%dT%H:%M:%SZ")

    authors_block = authors.map { |a| "    #{JSON.generate(a)}" }.join(",\n")
    File.write("authors.json", <<~JSON)
      {
        "updated_at": #{JSON.generate(data['updated_at'])},
        "authors": [
      #{authors_block}
        ],
        "signature": #{JSON.generate(data['signature'])}
      }
    JSON

    Dir.chdir("java") do
      sh "gradle signAuthorsJson"
    end
  end
end
