package com.keepit.shoebox.data.keep

case class NewKeepView(
  keep: NewKeepInfo,
  viewer: NewKeepViewerInfo,
  content: NewPageContent,
  context: NewPageContext)

