package com.keepit.test

import java.io.File
import com.keepit.dev.DevGlobal

class DevApplication(path: File = new File("./modules/common/")) extends TestApplication(new TestGlobal(DevGlobal.modules: _*), path = path)
