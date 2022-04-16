require 'erb'
require 'json'

file = File.read('./exported_commands.json')
loaded_commands = JSON.parse(file)

result = ERB.new(File.read('./command_docs.md.erb'), trim_mode: '-')
   .result_with_hash(commands: loaded_commands)

File.write('./command_docs.md', result)
