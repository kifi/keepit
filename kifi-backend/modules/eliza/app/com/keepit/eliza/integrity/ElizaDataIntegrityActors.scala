package com.keepit.eliza.integrity

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance

class ElizaDataIntegrityActors @Inject() (
  val messageThreadByMessage: ActorInstance[ElizaMessageThreadByMessageIntegrityActor])

