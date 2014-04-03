package com.keepit.cortex

import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule


abstract class CortexModule() extends ConfigurationModule with CommonServiceModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
}
