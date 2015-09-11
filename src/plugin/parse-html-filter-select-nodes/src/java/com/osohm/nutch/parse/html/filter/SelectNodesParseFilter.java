package com.osohm.nutch.parse.html.filter;

import java.util.ArrayList;
import java.util.StringTokenizer;

import java.net.URL;
import java.net.MalformedURLException;

import org.apache.hadoop.conf.Configuration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

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
 * SelectNodesParseFilter 
 * Class to filter previously parsed data and select which content we 
 * want to include based on our select-node properties. 
 * We can exclude content and links based on a blacklist or whitelist and 
 * we can select nodes content to copy to a metadata field to add to our 
 * parsedData. (nodes selection happens first, nodesExclusion happens 
 * second).
 * 
 * Please Note: This code is largely based on all the source code and 
 * discussion under {@link https://issues.apache.org/jira/browse/NUTCH-585}
 * Credits: Elisabeth Adler and other contributors.
 * 
 * @author Camilo Tejeiro 
 **********************************************************************/
public class SelectNodesParseFilter implements HtmlParseFilter
{
	// log id.
    public static final Log LOG = LogFactory.getLog("com.osohm.nutch");

	// parseFilter user configurations.
    private Configuration conf;

	// The list of nodes to whitelist or blacklist.
	// Each array has 3 position:
	// 1st: tag name
	// 2nd: attribute name
	// 3rd: attribute value 
    private String[][] nodesExcludeList;
	
	// var to track our exclusion mode 
	// whitelist(2), 
	// blacklist(1), or
	// disabled mode(0).
	private int nodesExcludeMode; 
	
	// The tag information we will use to select user-defined nodes for copying.
    private String[][] nodesSelectList;	
	
	// The field name to copy the selected nodes' data to.
	private String[] copyFieldsList;

	// var to store our unique selected nodes text content.
	private String [] nodesSelectTextList;

	// var to track nodes select mode
	// select enabled(1), or 
	// select disabled (0).
	private int nodesSelectMode;
	
	// HTML document object model utilties for manipulating the html tree.
	private DOMContentUtils utils;
	
    @Override
    public ParseResult filter(Content content, ParseResult parseResult, 
		HTMLMetaTags metaTags, DocumentFragment root)
    {	
		// check crawl condittions for applying our plugin.		
		boolean crawlAllowed = (metaTags.getNoIndex() == false || metaTags.getNoFollow() == false);	
		
		// check to see if we actually need to apply our parse filter plugin.
		// Otherwise just skip.
		if (crawlAllowed == true && (this.nodesSelectMode != 0 || this.nodesExcludeMode != 0))
		{		
			// Document object where we will store our Filtered DOM node.
			DocumentFragment filteredRoot = null;
			
			// initialize out selected text list now that we now the length. 
			this.nodesSelectTextList = new String[this.nodesSelectList.length];		
			
			// Do we need to apply our select node filter.
			if (this.nodesSelectMode == 1)
			{
				nodesSelectCopy(root);
			}
			
			// Do we need to apply our blacklist exclusion filters?					
			if (this.nodesExcludeMode == 1)
			{
				// Document Fragment for Blacklisted Content. (clone structure and content)
				filteredRoot = (DocumentFragment) root.cloneNode(true);
				nodesExclude(root, filteredRoot, this.nodesExcludeMode);
				
			}
			// Do we need to apply our whitelist exclusion filters?	
			else if (this.nodesExcludeMode == 2)
			{
				// Document Fragment for Whitelisted Content. (clone structure only)
				filteredRoot = (DocumentFragment) root.cloneNode(false);
				nodesExclude(root, filteredRoot, this.nodesExcludeMode);					
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
			
			// Parse Filter complete. 
			// get our original parsed output.
			Parse originalParseOutput = parseResult.get(content.getUrl());
			// get out original parsed data.
			ParseData originalParseData = originalParseOutput.getData(); 
			
			// create our filtered parseData, copy all fields except for outlinks, text.
			ParseData filteredParseData = new ParseData(originalParseData.getStatus(), 
				originalParseData.getTitle(), filteredOutlinks, originalParseData.getContentMeta(), 
				originalParseData.getParseMeta());		

			for (int i = 0; i < this.copyFieldsList.length ; ++i )
			{
				// add our content select-copy metadata.
				filteredParseData.getContentMeta().set(copyFieldsList[i], nodesSelectTextList[i]);				
			}

			// Generate our cleanParseResult
			ParseResult filteredParseResult = ParseResult.createParseResult(content.getUrl(),
			new ParseImpl(filteredText, filteredParseData));
			
			return filteredParseResult;
		}
		else
		{
			// Filter condittions not met, return unfiltered data.
			return parseResult;
		}		
	}
	
	/*******************************************************************
	 * Nodes Exclude
	 * Function which takes the original root node, traverses through the 
	 * complete DOM tree and applies the following based on exclude mode:
	 * Blacklist mode: removes element nodes which match the user-defined 
	 * html tags and attributes.
	 * Whitelist mode: keeps only the element node which match the 
	 * user-defined html tags and attributes.
	 * @param rootNode 		the root parent of the html document. 
	 * @param newNode		the cloned node we should exclude nodes from.
	 * @param excludeMode	Selection between blacklist or whitelist mode.
	 ******************************************************************/
	private void nodesExclude(Node rootNode, Node newNode, int excludeMode)
	{
		// the current node we are analyzing.
		Node currentNode;
		// the name of the current node.
		String currentNodeName;
		// the type of our current node. 
		short currentNodeType;
		// a map of attribute nodes from our current node.
		NamedNodeMap attributeNodeMap;
		// our desired attribute node from our node map.
		Node attributeNode;
			
		// our walker to traverse the DOM node tree.  
		NodeWalker nodeWalker;
		
		// check to set root source for our nodeWalker based on mode.
		if (excludeMode == 1)
			nodeWalker = new NodeWalker(newNode);	
		else if (excludeMode == 2)
			nodeWalker = new NodeWalker(rootNode);
		else
			return;
				
		while(nodeWalker.hasNext())
		{
			// update our current node
			currentNode = nodeWalker.nextNode();
			
			// ge the node type.
			currentNodeType = currentNode.getNodeType();
			
			// now we want to limit our search to element nodes (HTML tags) 
			// with attributes (attribute nodes).
			// note that the first check is optional, but we have it 
			// here for clarity purposes.
			if (currentNodeType == Node.ELEMENT_NODE 
				&& currentNode.hasAttributes() == true)
			{
				// get the node name
				currentNodeName = currentNode.getNodeName();
				
				// get the attributes node map.
				attributeNodeMap = currentNode.getAttributes();
				
				// ok, now compare our current Node and attributes with 
				// user defined ones.
				for (int i = 0; i < this.nodesExcludeList.length ; ++i )
				{
					attributeNode = attributeNodeMap.getNamedItem(this.nodesExcludeList[i][1]);
					
					// now check if the html tag (element node) the attribute 
					// name and the attribute value are the same.
					if (currentNodeName.equalsIgnoreCase(this.nodesExcludeList[i][0]) 
						&& attributeNode != null 
						&& attributeNode.getNodeValue().equalsIgnoreCase(this.nodesExcludeList[i][2]))
					{
						
						if (excludeMode == 1)
						{
							// can't remove this node, but we can strip it
							if (LOG.isTraceEnabled())
								LOG.trace("Node Blacklisted, Stripping " + currentNodeName + "#" + attributeNode.getNodeValue());
							
							currentNode.setNodeValue("");
							
							// and remove any children nodes.
							while (currentNode.hasChildNodes())
								currentNode.removeChild(currentNode.getLastChild());							
						}
						else
						{
							// we have found our element node, copy and skip 
							// children
							if (LOG.isTraceEnabled())
								LOG.trace("Node Whitelisted, Keeping " + currentNodeName + "#" + attributeNode.getNodeValue());
							newNode.appendChild(currentNode.cloneNode(true));
						}
						
						// since we found our node and processed it, no need 
						// to go deeper.
						nodeWalker.skipChildren();
					
					}
					
				}
			}
		}	
	}
	
	/*******************************************************************
	 * Nodes Select Copy.
	 * Function which takes the original root node, traverses through the 
	 * complete DOM tree and copies specific elements desired by the user 
	 * to an array of text strings for later inclusion into the parse 
	 * Metadata as individual fields.
	 * @param rootNode the root parent of the html document. 
	 ******************************************************************/
	private void nodesSelectCopy(Node rootNode)
	{
		// the current node we are analyzing.
		Node currentNode;
		// the name of the current node.
		String currentNodeName;
		// the type of our current node. 
		short currentNodeType;
		// a map of attribute nodes from our current node.
		NamedNodeMap attributeNodeMap;
		// our desired attribute node from our node map.
		Node attributeNode;
		
		// our walker to traverse the DOM node tree.  
		NodeWalker nodeWalker = new NodeWalker(rootNode);				
		
		while(nodeWalker.hasNext())
		{
			// update our current node
			currentNode = nodeWalker.nextNode();
			
			// ge the node type.
			currentNodeType = currentNode.getNodeType();		
			// now we want to limit our search to element nodes (HTML tags) 
			// with attributes (attribute nodes).
			// note that the first check is optional, but we have it 
			// here for clarity purposes.
			
			if (currentNodeType == Node.ELEMENT_NODE 
				&& currentNode.hasAttributes() == true)
			{
				// get the node name
				currentNodeName = currentNode.getNodeName();
				
				// get the attributes node map.
				attributeNodeMap = currentNode.getAttributes();
				
				// ok, now compare our current Node and attributes with 
				// user defined ones.
				for (int i = 0; i < this.nodesSelectList.length ; ++i )
				{
					attributeNode = attributeNodeMap.getNamedItem(this.nodesSelectList[i][1]);
					
					// now check if the html tag (element node) the attribute 
					// name and the attribute value are the same.
					if (currentNodeName.equalsIgnoreCase(this.nodesSelectList[i][0]) 
						&& attributeNode != null 
						&& attributeNode.getNodeValue().equalsIgnoreCase(this.nodesSelectList[i][2]))
					{
						if (LOG.isTraceEnabled())
						{
							LOG.trace("Node Selected, Copying Text From: " 
								+ currentNodeName + "#" + attributeNode.getNodeValue());
						}
						
						StringBuffer stringBuffer = new StringBuffer();
						utils.getText(stringBuffer, currentNode);
						this.nodesSelectTextList[i] = stringBuffer.toString();
						
						// since we found our node and processed, no need 
						// to go deeper.
						nodeWalker.skipChildren();
							
					}
				}
				
			}					
		}		
	}

	/*******************************************************************
	* Set Configuration
	* Function called to load the plugin properties from our 
	* nutch configuration files (nutch-default.xml, nutch-site.xml)
	* @param conf 	Hadoop-nutch configuration to get our plugin properties.
	*******************************************************************/    
    public void setConf(Configuration conf)
    {
        this.conf = conf;     
        
		this.utils = new DOMContentUtils(conf);
        
        // initialize our global configurations.
        this.nodesExcludeList = null;
        this.nodesExcludeMode = 0;
		this.nodesSelectList = null;	
		this.copyFieldsList = null;
		this.nodesSelectTextList = null;
		this.nodesSelectMode = 0;
     
        // check for any custom main heading tag to include in out parsed data.
        String nodesSelectString = conf.get("parser.html.nodes.select.copy", null);
                
		// Check the chosen exclude mode if any.
		String excludeModeString = conf.get("parser.html.nodes.exclude.mode", null);
		// And gather the list of nodes if applicable.
		String nodesExcludeString = conf.get("parser.html.nodes.exclude.list", null);
        
        
        if (nodesSelectString != null && nodesSelectString.isEmpty() == false)
		{
			if (LOG.isInfoEnabled()) 
			{
				LOG.info("Configured using [parser.html.nodes.select.copy] to take" 
					+ nodesSelectString + " as select nodes > copy fields");		
			}
			
			// enable our select functionality.
			this.nodesSelectMode = 1;
			
			// Parse out our nodes by the | string divider.
			StringTokenizer stringTokenizer = new StringTokenizer(nodesSelectString , "|");			
			
			// Initialize our global arrays now that we know the size.
			this.nodesSelectList = new String[stringTokenizer.countTokens()][];						
			this.copyFieldsList = new String[stringTokenizer.countTokens()];			
			
			// Now split each node by the ; divider to get the tag name, 
			// the attribute name, the attribute value and the destination field.
			int i = 0;
			while ( stringTokenizer.hasMoreTokens() )
			{	
				// populate the html attributes.
				this.nodesSelectList[i] = stringTokenizer.nextToken().split(";");
				
				// Create the field name from the given tag attributes.
				this.copyFieldsList[i] = nodesSelectList[i][0] + "_" 
					+ nodesSelectList[i][1] + "_" + nodesSelectList[i][2];

				i++;
			}		
		}	
        
		// Check if html parser exclude properties were set correctly. 
		if ((excludeModeString != null) && (excludeModeString.isEmpty() == false) 
			&& (nodesExcludeString != null) && (nodesExcludeString.isEmpty() == false))
		{
			if (excludeModeString.equalsIgnoreCase("blacklist") || excludeModeString.equalsIgnoreCase("whitelist"))
			{
				if (LOG.isInfoEnabled()) 
				{
					LOG.info("Configured using [parser.html.nodes.exclude.mode] to " 
						+ excludeModeString + " DIVs with IDs [" + nodesExcludeString + "]...");
				}
				
				// set out excludeMode number at config time.
				if (excludeModeString.equalsIgnoreCase("blacklist"))
					this.nodesExcludeMode = 1;
				else
					this.nodesExcludeMode = 2;

				// Parse out our nodes by the | string divider.
				StringTokenizer stringTokenizer = new StringTokenizer(nodesExcludeString , "|");
				
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
					
				this.nodesExcludeMode = 0;	
			}			
		}
    }
    
	/*******************************************************************
	* Get Configuration
	* Convenience getter method for accesing the top level configuration 
	* in this class.
	* @return	The hadoop-nutch configuration.
	*******************************************************************/  	
	public Configuration getConf()
    {
        return this.conf;
    }
}
