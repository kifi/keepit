# Description:
#    Utility to automatically deploy a service
#
# Commands:
#    hubot deploy <service> - Starts a deploy for the given service
#    hubot deploy <service> inprog - Starts a deploy for the given service, only when the current build finishes
#    hubot deploy <service> only <host> - Starts a deploy for the given service, only for the given host
#    hubot deploy <service> version <version> - Starts a deploy for the given service with the given version
#    hubot deploy <service> only <host> version <version> - Starts a deploy for the given service with the given version, only for the given host
#    hubot stop deploys - Kills any further deployments
#    hubot stop builds - Kills all current builds
#    hubot build - Builds all-quick-s3
#    hubot clean - Builds clean-all-quicks

{spawn} = require 'child_process'

exec = (command) ->
  [command, args...] = command.split(' ')

  spawn command, args, {}

module.exports = (robot) ->

  robot.respond /stop deploys/i, (res) ->
    res.reply "Stopping all further deployments"
    (require 'child_process').exec("sudo pkill -f 'python.*eddie'")

  robot.respond /stop builds?/i, (res) ->
    res.reply "Stopping all current builds"
    robot.http("http://localhost:8080/computer/(master)/executors/2/stop")
      .header('Accept', '*.*')
      .post() (err, res, body) ->
    robot.http("http://localhost:8080/computer/(master)/executors/1/stop")
      .header('Accept', '*.*')
      .post() (err, res, body) ->
    robot.http("http://localhost:8080/computer/(master)/executors/0/stop")
      .header('Accept', '*.*')
      .post() (err, res, body) ->

  robot.respond /build/i, (res) ->
    robot.http("http://localhost:8080/job/all-quick-s3/build?delay=0sec")
      .header('Accept', '*.*')
      .get() (err, res, body) ->
        res.reply "Building all-quick-s3"

  robot.respond /clean/i, (res) ->
    robot.http("http://localhost:8080/job/clean-trigger-quicks/build?delay=0sec")
      .header('Accept', '*.*')
      .get() (err, res, body) ->
        res.reply "Cleaning everything :scream_cat:"

  robot.respond /.*?deploy ([a-zA-Z0-9_-]*)(?:.*? only ([a-zA-Z0-9_-]*))?(.*? inprog)?(?:.*? version ([a-zA-Z0-9_-]*))?.*$/, (res) ->

    console.log(res.match)
    service = res.match[1]
    host = res.match[2]
    inprog = res.match[3]
    version = res.match[4]
    user = res.message.user.name

    reply_msg = "Starting deploy for '#{service}'"
    command = "/home/eng/bin/deploy #{service} --iam #{user}-via-eddie"

    if host isnt undefined
      reply_msg += " only on host '#{host}'"
      command += " --host #{host}"

    if inprog isnt undefined
      reply_msg += " with the in-progress build"
      command += " --inprog"

    if version isnt undefined
      reply_msg += " with version '#{version}'"
      command += " --version #{version}"

    res.reply reply_msg
    exec command
