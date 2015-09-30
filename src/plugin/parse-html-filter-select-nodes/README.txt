Parse-Html-Filter-Select-Nodes plugin
==================================

This plugin has been created based on multiple sources from this Jira Thread.
Refer to: https://issues.apache.org/jira/browse/NUTCH-585

Reference Patches: blacklist-whitelist-plugin, nutch-585-excludeNodes, 
	nutch-585-jostens-excludeDIVs

Credits: Elisabeth Adler and other contributors.			

The Parse-Html-Filter-Select-Nodes plugin, takes a set of configurations to 
either strip out content from certain html tags (by blacklisting those tags) 
or to allow content from certain html tags (by whitelisting those tags). 

It also allows you to select unique tags to add to metadata fields.

I have modified the source such that the returned Text and outlinks 
from the HTMLParseFilter overwrite the original HTML Parser Text and Outlinks, 
this is because I am currently using nutch for a focused crawl as opossed 
to a web wide crawl. 
