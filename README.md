Apache_Nutch-1.10 Install Dir.
==============================

This is a working clone of the Apache_Nutch-1.10 source modified 
for my purposes. 

It includes a working plugin for performing a focused crawl with nutch:
parse-html-filter-select-nodes 

This plugin allows you to both exclude certain html tags from going into 
the content or outlink fields (to exclude footers, tables of contents ...) 
and select certain html tags to copy their content to a metata field 
(e.g unique headers ...). 

I haven't created a patch for it, for now you can just clone or fork this 
repo and then do a git diff between your apache nutch installation and this 
one, then you can see which changes you need to make to apply the plugin.

Please refer to the original source at: https://github.com/apache/nutch
