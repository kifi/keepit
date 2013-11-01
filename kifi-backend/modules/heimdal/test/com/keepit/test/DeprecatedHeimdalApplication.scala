package com.keepit.test

import java.io.File
import com.keepit.dev.HeimdalDevGlobal

class DeprecatedHeimdalApplication() extends DeprecatedTestApplication(new DeprecatedTestRemoteGlobal(HeimdalDevGlobal.module), useDb = false, path = new File("./modules/heimdal/")) {

}

