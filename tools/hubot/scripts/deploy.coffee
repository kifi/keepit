module.exports = (robot) ->
  robot.respond /deploy ([a-zA-Z0-9_]*)$/, (res) ->
    service = res.match[1]
    user = res.message.user.name
    res.reply "Alright, kicking off deploy for service #{service}."
    res.reply "You should see the logs in #deploy real soon"
    @exec = (require 'child_process').exec
    @exec "deploy #{service} --iam #{user}-via-hubot", () ->