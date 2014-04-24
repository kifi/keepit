package com.keepit.cortex


import com.google.inject.{Provides, Singleton}

case class FakeCortexServiceClientModule() extends CortexServiceClientModule {
  def configure(){}

  @Singleton
  @Provides
  def cortexServiceClient(): CortexServiceClient = {
    new FakeCortexServiceClientImpl()
  }

}
