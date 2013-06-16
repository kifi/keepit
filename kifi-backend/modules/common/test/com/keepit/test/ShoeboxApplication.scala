package com.keepit.test

import java.io.File
import com.keepit.dev.ShoeboxDevGlobal

class ShoeboxApplication() extends TestApplication(new TestGlobal(ShoeboxDevGlobal.modules: _*), path = new File("./modules/common/"))

