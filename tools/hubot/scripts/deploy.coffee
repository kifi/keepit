# Description:
#    Utility to automatically deploy a service
#
# Commands:
#    hubot deploy <service> - Starts a deploy for the given service
#    hubot deploy <service> only <host> - Starts a deploy for the given service, only for the given host

{spawn} = require 'child_process'

exec = (command) ->
  [command, args...] = command.split(' ')

  spawn command, args, {}

module.exports = (robot) ->

  robot.respond /deploy ([^ ]*)(?: only ([^ ]*))?$/, (res) ->

    service = res.match[1]
    host = res.match[2] ? 'all'
    user = res.message.user.name

    if host is 'all'
      res.reply "Starting deploy for #{service}"
    else
      res.reply "Starting deploy for #{service}, only on host #{host}"

    exec "deploy #{service} --iam #{user}-eddie-auto --host #{host}"