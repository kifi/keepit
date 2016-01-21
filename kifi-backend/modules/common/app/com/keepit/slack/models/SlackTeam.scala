package com.keepit.slack.models

import com.keepit.common.db.Id
import com.keepit.model.Organization
import com.kifi.macros.json

@json case class SlackTeamId(value: String)
@json case class SlackTeamName(value: String)
@json case class SlackTeamDomain(value: String)
@json case class SlackTeamEmailDomain(value: String)
@json case class InternalSlackTeamInfo(orgId: Option[Id[Organization]], teamName: SlackTeamName) // augment as needed

