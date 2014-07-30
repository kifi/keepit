package com.keepit.test

import java.io.File
import com.keepit.dev.DevGlobal
import com.google.inject.Module

class DevApplication(overridingModules: Module*)(implicit path: File = new File("./modules/common/"))
  extends DbTestApplication(path, overridingModules, Seq(DevGlobal.module))
