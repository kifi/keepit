package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.graph.GraphModule
import com.keepit.common.store.GraphDevStoreModule
import com.keepit.graph.simple.SimpleGraphDevModule

case class GraphDevModule() extends GraphModule(GraphDevStoreModule(), SimpleGraphDevModule()) with CommonDevModule
