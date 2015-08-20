package com.keepit.model

import com.keepit.common.store.ImagePath
import com.kifi.macros.json

@json case class LibraryImageInfo(path: ImagePath, x: Int, y: Int)
