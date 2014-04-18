package com.keepit.common.cache

case class GraphCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules:_*) {

}
