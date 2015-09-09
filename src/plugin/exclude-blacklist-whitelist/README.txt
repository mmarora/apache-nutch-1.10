Exclude-blacklist-whitelist plugin
==================================

This plugin has been created based on multiple sources from this Jira Thread.
Refer to: https://issues.apache.org/jira/browse/NUTCH-585
Credits: Elisabeth Adler and other contributors.

The exclude blacklist whitelist plugin, takes a set of configurations to 
either strip out content from certain html tags (by blacklisting those tags) 
or to allow content from certain html tags (by whitelisting those tags).

I have modified the source such that the returned Text and outlinks 
from the HTMLParseFilter overwrite the original HTML Parser Text and Outlinks, 
this is because I am currently using nutch for a focused crawl as opossed 
to a web wide crawl. 

1. Enable the exclude-blacklist-whitelist plugin:
Edit your nutch-site.xml file and adding "|exclude-blacklist-whitelist" to the property "plugin.includes".

Example:
<property>
  <name>plugin.includes</name>
  <value>protocol-http|urlfilter-regex|parse-html|index-(basic|anchor)|query-(basic|site|url)|response-(json|xml)|summary-basic|scoring-opic|urlnormalizer-(pass|regex|basic)|exclude-blacklist-whitelist</value>
</property>

2. Define the blacklist or whitelist:
To define a blacklist or whitelist, specify the exclude mode 'parser.html.nodes.exclude.mode'
To be either 'whitelist' or 'blacklist' the default is empty, or no exclude. 
Please note: You can only have one exclude mode.

Examples: To set the exclude mode to whitelisting you can do
<property>
	<name>parser.html.nodes.exclude.mode</name>
	<value>whitelist</value>
	<description>
		Mode for excluding HTML Nodes: empty '' for no excludes (default behavior), 
		'blacklist' for excluding the nodes listed and 'whitelist' for allowing only 
		the nodes listed. Choose only one of these three values and set the 
		nodes list in the parser.html.nodes.exclude.list property.  
	</description>
</property>	

3. Solr will have the stripped data in the default mapped content field.
