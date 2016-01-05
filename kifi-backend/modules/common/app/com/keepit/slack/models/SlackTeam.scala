package com.keepit.slack.models

import com.kifi.macros.json

@json case class SlackTeamId(value: String)
@json case class SlackTeamName(value: String)
@json case class SlackTeamDomain(value: String)
@json case class SlackTeamEmailDomain(value: String)
