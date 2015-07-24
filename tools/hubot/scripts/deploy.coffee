# Description:
#    Utility to automatically deploy a service
#
# Commands:
#    hubot deploy <service> - Starts a deploy for the given service
#    hubot deploy <service> only <host> - Starts a deploy for the given service, only for the given host
#    hubot deploy <service> version <version> - Starts a deploy for the given service with the given version
#    hubot deploy <service> only <host> version <version> - Starts a deploy for the given service with the given version, only for the given host

{spawn} = require 'child_process'

exec = (command) ->
  [command, args...] = command.split(' ')

  spawn command, args, {}

module.exports = (robot) ->

  robot.respond /deploy ([^ ]*)(?: only ([^ ]*))?(?: version ([^ ]*))?$/, (res) ->

    service = res.match[1]
    host = res.match[2]
    version = res.match[3]
    user = res.message.user.name

    reply_msg = "Starting deploy for #{service}"
    command = "/home/eng/bin/deploy #{service} --iam #{user}-via-eddie"

    if version isnt undefined
      reply_msg += " with version #{version}"
      command += " --version #{version}"

    if host isnt undefined
      reply_msg += " only on host #{host}"
      command += " --host #{host}"

    res.reply reply_msg
    exec command
