package com.keepit.export

import com.keepit.common.db.Id
import com.keepit.model.{ Keep, Library, NormalizedURI }
import com.keepit.rover.model.RoverUriSummary

final case class FullKifiExport(
  libs: Map[Id[Library], Library],
  keeps: Map[Id[Keep], Keep],
  uris: Map[Id[NormalizedURI], RoverUriSummary])
