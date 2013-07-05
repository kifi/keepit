package com.keepit.test

import java.io.File
import com.keepit.dev.DevGlobal

class DevApplication(path: File = new File("./modules/common/")) extends DeprecatedTestApplication(new DeprecatedTestGlobal(DevGlobal.module), path = path)
