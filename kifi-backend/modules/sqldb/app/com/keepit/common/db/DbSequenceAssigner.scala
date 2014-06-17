package com.keepit.common.db

import com.keepit.common.db.slick.{Database, DbRepo, SeqNumberFunction}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SequenceAssigner

abstract class DbSequenceAssigner[M <: ModelWithSeqNumber[M]](
  db: Database,
  repo: DbRepo[M] with SeqNumberFunction[M],
  override val airbrake: AirbrakeNotifier
) extends SequenceAssigner {

  val batchSize: Int = 20 // override this if necessary
  
  override def assignSequenceNumbers(): Unit = {
    while (db.readWrite{ implicit session => repo.assignSequenceNumbers(batchSize) } > 0) {}
  }
}
