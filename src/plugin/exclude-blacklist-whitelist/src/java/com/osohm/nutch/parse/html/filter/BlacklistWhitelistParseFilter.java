package com.osohm.nutch.parse.html.filter;

import java.util.ArrayList;
import java.util.StringTokenizer;

import java.net.URL;
import java.net.MalformedURLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;

import org.apache.nutch.parse.Outlink;
import org.apache.nutch.parse.ParseResult;
import org.apache.nutch.parse.ParseData;
import org.apache.nutch.parse.ParseImpl;
import org.apache.nutch.parse.ParseStatus;
import org.apache.nutch.parse.HtmlParseFilter;
import org.apache.nutch.parse.HTMLMetaTags;
import org.apache.nutch.parse.Parse;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.util.NodeWalker;

import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.NamedNodeMap;

/***********************************************************************
 * BlackList White List Html Parse Filter. 
 * Class to filter previously parsed content and exclude content and links 
 * based on a blacklist or whitelist. Content and outlinks overwrite the 
 * original parseHtml values.
 * If a blacklist configuration is provided, all elements plus their 
 * subelements are not included in the final content field which is indexed. 
 * If a whitelist configuration is provided, only the elements
 * and their subelements are included in the indexed field.
 * Please Note: This code is largely based on all the source code and 
 * discussion under {@link https://issues.apache.org/jira/browse/NUTCH-585}
 * Credits: Elisabeth Adler
 * 
 * @author Camilo Tejeiro 
 **********************************************************************/
public class BlacklistWhitelistParseFilter implements HtmlParseFilter
{
    public static final Log LOG = LogFactory.getLog("com.osohm.nutch");

	// var to store our parseFilter user configurations.
    private Configuration conf;

	// The list of nodes to whitelist or blacklist.
	// Each array has 3 position:
	// 1st: tag name
	// 2nd: attribute name
	// 3rd: attribute value 
    private String[][] nodesExcludeList;
	
	// var to track whitelist or blacklist mode.
	private String nodesExcludeMode; 
	
	private DOMContentUtils utils;
	
    @Override
    public ParseResult filter(Content content, ParseResult parseResult, 
		HTMLMetaTags metaTags, DocumentFragment root)
    {
				
		DocumentFragment filteredRoot = null;
		
		// check to see if we can index or follow links in this page, otherwise just skip.
		if (metaTags.getNoIndex() == false || metaTags.getNoFollow() == false)
		{
			// Check for any parser exclusions, Is our HTML Parser Exclude Enabled?
			if ((this.nodesExcludeMode != null) && (this.nodesExcludeMode.isEmpty() == false) 
				&&(this.nodesExcludeList != null) && (this.nodesExcludeList.length > 0))
			{
				
				if (this.nodesExcludeMode.equalsIgnoreCase("blacklist"))
				{
					// Document Fragment for Blacklisted Content. (clone structure and content)
					DocumentFragment rootBlackListed = null;
					rootBlackListed = (DocumentFragment) root.cloneNode(true);
					
					if (LOG.isTraceEnabled()) 
						LOG.trace("Stripping Blacklisted Nodes...");
					stripBlacklistedNodes(rootBlackListed);
					
					filteredRoot = rootBlackListed;
					
				}	
				else if (this.nodesExcludeMode.equalsIgnoreCase("whitelist"))
				{
					// Document Fragment for Whitelisted Content. (clone structure only)
					DocumentFragment rootWhiteListed = null;
					rootWhiteListed = (DocumentFragment) root.cloneNode(false);
					
					if (LOG.isTraceEnabled()) 
						LOG.trace("Copying Whitelisted Nodes...");
					copyWhitelistedNodes(root, rootWhiteListed);
					
					// now set our root to point to our copied whitelisted Nodes.
					filteredRoot = rootWhiteListed;	
				}
				else  	
				{
					// Missconfigured exclude mode, skip applying filter.
					LOG.error("Missconfigured [parser.html.NodesExcludeMode] property: " 
					+ nodesExcludeMode + " ... returning original parseResult");
					return parseResult;
				}
			}
			else
			{
				return parseResult;
			}
			
			
			Outlink[] filteredOutlinks = new Outlink[0];
		
			String filteredText = "";
			
			URL base;
			
			try 
			{
				base = new URL(content.getBaseUrl());
			} 
			catch (MalformedURLException e) 
			{
				return new ParseStatus(e)
				.getEmptyParseResult(content.getUrl(), getConf());
			}			
			
			// check meta directives
			if (metaTags.getNoIndex() == false) 
			{ 
				// is it okay to index?
				StringBuffer stringBuffer = new StringBuffer();
				if (LOG.isTraceEnabled()) 
					LOG.trace("Getting text...");
				// extract text
				utils.getText(stringBuffer, filteredRoot); 
				filteredText = stringBuffer.toString();
			}
			
			if (!metaTags.getNoFollow()) 
			{              
				// is it okay to follow links?
				// extract outlinks
				ArrayList<Outlink> arrayList = new ArrayList<Outlink>();   
				URL baseTag = utils.getBase(filteredRoot);
				if (LOG.isTraceEnabled())  
					LOG.trace("Getting links..."); 

				utils.getOutlinks(baseTag!=null?baseTag:base, arrayList, filteredRoot);
				filteredOutlinks = arrayList.toArray(new Outlink[arrayList.size()]);
				if (LOG.isTraceEnabled()) 
				{
					LOG.trace("found "+filteredOutlinks.length 
						+ " filtered outlinks in " + content.getUrl());
				}
			}
			
			// ParseExclude complete. 
			// get our original parsed output.
			Parse originalParseOutput = parseResult.get(content.getUrl());
			// get out original parsed data.
			ParseData originalParseData = originalParseOutput.getData(); 
			
			// create our filtered parseData, copy all fields except for outlinks.
			ParseData filteredParseData = new ParseData(originalParseData.getStatus(), 
				originalParseData.getTitle(), filteredOutlinks, originalParseData.getContentMeta(), 
				originalParseData.getParseMeta());

			// Generate our cleanParseResult
			ParseResult filteredParseResult = ParseResult.createParseResult(content.getUrl(),
			new ParseImpl(filteredText, filteredParseData));
			
			return filteredParseResult;
		}
		else
		{
			// Page does not allow index or follow, return unfiltered data.
			return parseResult;
		}		
	}
	
	protected void stripBlacklistedNodes(Node pNode) 
	{
		// Initialize to false by default to continue through nested tags if no match.
		boolean wasStripped = false;
		
		// Go over your list of nodes to exclude.
		for ( int i = 0 ; i < this.nodesExcludeList.length ; ++i )
		{
			// Does the current node have the blacklisted tag.
			if (this.nodesExcludeList[i][0].equalsIgnoreCase(pNode.getNodeName()) && pNode.hasAttributes()) 
			{
				// Does the current node have the blacklisted attribute name.
				Node idNode = pNode.getAttributes().getNamedItem(this.nodesExcludeList[i][1]);	
				if (idNode != null)
				{
					String idValue = idNode.getNodeValue();					
					if (idValue != null) 
					{
						// does the current node have the blacklisted attribute value.
						if ( idValue.equalsIgnoreCase(this.nodesExcludeList[i][2]) ) 
						{
							// can't remove this node, but we can strip it
							if (LOG.isTraceEnabled())
								LOG.trace("Stripping " + pNode.getNodeName() + "#" + idNode.getNodeValue());
							pNode.setNodeValue("");

							// remove all children for this node
							while (pNode.hasChildNodes())
								pNode.removeChild(pNode.getFirstChild());

							
							wasStripped = true;
							break;
						}
					}
				}
			}
		}

		// Did we strip the top level tag?
		if (wasStripped == false) 
		{
			// If we didn't, process the children tags recursively.
			NodeList children = pNode.getChildNodes();
			if (children != null) 
			{
				int len = children.getLength();
				for (int i = 0; i < len; i++) 
				{
					stripBlacklistedNodes(children.item(i));
				}
			}
		}		
	}

	protected void copyWhitelistedNodes(Node pNode, Node newNode) 
	{
		// Initialize to false by default to continue through nested tags if no match.
		boolean wasFound = false;
		
		// Go over your list of nodes.
		for ( int i = 0 ; i < this.nodesExcludeList.length ; ++i )
		{
			// Does the current node have the whitelisted tag.
			if (this.nodesExcludeList[i][0].equalsIgnoreCase(pNode.getNodeName()) && pNode.hasAttributes()) 
			{
				// Does the current node have a whitelisted attribute name.
				Node idNode = pNode.getAttributes().getNamedItem(this.nodesExcludeList[i][1]);	
				if (idNode != null)
				{
					String idValue = idNode.getNodeValue();					
					if (idValue != null) 
					{
						// does the current node have the whitelisted attribute value.
						if ( idValue.equalsIgnoreCase(this.nodesExcludeList[i][2]) ) 
						{
							//  Copy this node.
							if (LOG.isTraceEnabled())
								LOG.trace("Copying " + pNode.getNodeName() + "#" + idNode.getNodeValue());
							newNode.appendChild(pNode.cloneNode(true));
							wasFound = true;

							break;
						}
					}
				}
			}
		}

		// Did we find the top level tag?
		if (wasFound == false) 
		{
			// If we didn't, process the children tags recursively.
			NodeList children = pNode.getChildNodes();
			if (children != null) 
			{
				int len = children.getLength();
				for (int i = 0; i < len; i++) 
				{
					copyWhitelistedNodes(children.item(i), newNode);
				}
			}
		}		
	}
	
    public void setConf(Configuration conf)
    {
        this.conf = conf;     
        
		this.utils = new DOMContentUtils(conf);
        
        // initialize our list of nodes.
        this.nodesExcludeList = null;
        
		// first check the chosen exclude mode if any.
		String excludeMode = getConf().get("parser.html.nodes.exclude.mode", null);
		// And gather the list of nodes if applicable.
		String nodesList = getConf().get("parser.html.nodes.exclude.list");
        
		// Check if html parser exclude properties were set correctly. 
		if ((excludeMode != null) && (excludeMode.isEmpty() == false) 
			&& (nodesList != null) && (nodesList.trim().length() > 0))
		{
			if (excludeMode.equalsIgnoreCase("blacklist") || excludeMode.equalsIgnoreCase("whitelist"))
			{
				LOG.info("Configured using [parser.html.nodes.exclude.mode] to " 
				+ excludeMode + " DIVs with IDs [" + nodesList + "]...");
				
				// Copy to a new global string with our exclude mode.
				this.nodesExcludeMode = new String(excludeMode);
				
				// Parse out our nodes by the | string divider.
				StringTokenizer stringTokenizer = new StringTokenizer(nodesList , "|");
				
				// Initialize our global array now that we know the size.
				this.nodesExcludeList = new String[stringTokenizer.countTokens()][];
			
				// Now split each node by the ; divider to get the tag name, 
				// the attribute name and the attribute value.
				int i = 0;
				while ( stringTokenizer.hasMoreTokens() )
				{
					this.nodesExcludeList[i] = stringTokenizer.nextToken().split(";");
					i++;
				}				
			}
			else
			{
				// Missconfigured exclude mode, skip applying exclude configs.
				LOG.error("Missconfigured [parser.html.nodes.exclude.mode] property: " 
					+ nodesExcludeMode + " ... keeping original configurations");
			}			
		}
    }
	
	public Configuration getConf()
    {
        return this.conf;
    }
}
