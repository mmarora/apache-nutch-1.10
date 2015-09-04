package at.scintillation.nutch;

import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.nutch.parse.HTMLMetaTags;
import org.apache.nutch.parse.HtmlParseFilter;
import org.apache.nutch.parse.Parse;
import org.apache.nutch.parse.ParseResult;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.util.NodeWalker;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Class to parse the content and apply a blacklist or whitelist. The content is stored in 
 * the index in the field "strippedContent".<br/>
 * If a blacklist configuration is provided, all elements plus their subelements are not included in the
 * final content field which is indexed. If a whitelist configuration is provided, only the elements
 * and their subelements are included in the indexed field.<br/><br/>
 * On the basis of {@link https://issues.apache.org/jira/browse/NUTCH-585}
 * 
 * @author Elisabeth Adler
 */
public class BlacklistWhitelistParser implements HtmlParseFilter
{

    public static final Log LOG = LogFactory.getLog("at.scintillation.nutch");

    private Configuration conf;

    private String[] blacklist;

    private String[] whitelist;

    @Override
    public ParseResult filter(Content content, ParseResult parseResult, HTMLMetaTags metaTags, DocumentFragment doc)
    {
        Parse parse = parseResult.get(content.getUrl());

        DocumentFragment rootToIndex = null;
        StringBuffer strippedContent = new StringBuffer();
        if ((this.whitelist != null) && (this.whitelist.length > 0))
        {
            LOG.info("Applying whitelist...");
            rootToIndex = (DocumentFragment) doc.cloneNode(false);
            whitelisting(doc, rootToIndex);
        }
        else if ((this.blacklist != null) && (this.blacklist.length > 0))
        {
            LOG.info("Applying blacklist...");
            rootToIndex = (DocumentFragment) doc.cloneNode(true);
            blacklisting(rootToIndex);
        }

        getText(strippedContent, rootToIndex); // extract text to index
        parse.getData().getContentMeta().set("strippedContent", strippedContent.toString());

        return parseResult;
    }

    /**
     * Traverse through the document and set all elements matching the given
     * blacklist configuration to empty
     * @param pNode Root node
     */
    private void blacklisting(Node pNode)
    {
        boolean wasStripped = false;
        String type = pNode.getNodeName().toLowerCase();
        String id = null;
        String className = null;
        if (pNode.hasAttributes())
        {
            Node node = pNode.getAttributes().getNamedItem("id");
            id = (node != null) ? node.getNodeValue().toLowerCase() : null;

            node = pNode.getAttributes().getNamedItem("class");
            className = (node != null) ? node.getNodeValue().toLowerCase() : null;
        }

        String typeAndId = type + "#" + id;
        String typeAndClass = type + "." + className;

        // check if the given element is in blacklist: either only the element type, or type and id or type and class
        boolean inList = false;
        if (type != null && Arrays.binarySearch(this.blacklist, type) >= 0)
            inList = true;
        else if (type != null && id != null && Arrays.binarySearch(this.blacklist, typeAndId) >= 0)
            inList = true;
        else if (type != null && className != null && Arrays.binarySearch(this.blacklist, typeAndClass) >= 0)
            inList = true;

        if (LOG.isTraceEnabled())
            LOG.trace("In blacklist: " + inList + " (" + type + " or " + typeAndId + " or " + typeAndClass + ")");

        if (inList)
        {
            // can't remove this node, but we can strip it
            if (LOG.isTraceEnabled())
                LOG.trace("Removing " + type + (id != null ? "#" + id : (className != null ? "." + className : "")));
            pNode.setNodeValue("");
            // remove all children for this node
            while (pNode.hasChildNodes())
                pNode.removeChild(pNode.getFirstChild());
            wasStripped = true;
        }

        if (!wasStripped)
        {
            // process the children recursively
            NodeList children = pNode.getChildNodes();
            if (children != null)
            {
                int len = children.getLength();
                for (int i = 0; i < len; i++)
                {
                    blacklisting(children.item(i));
                }
            }
        }
    }

    /**
     * Traverse through the document and copy all elements matching the given
     * whitelist configuration to the new node parameter, which will then only
     * contain all allowed nodes including all their children.
     * @param pNode Root node
     * @param newNode node containing only the allowed elements
     */
    private void whitelisting(Node pNode, Node newNode)
    {
        boolean wasStripped = false;
        String type = pNode.getNodeName().toLowerCase();
        String id = null;
        String className = null;
        if (pNode.hasAttributes())
        {
            Node node = pNode.getAttributes().getNamedItem("id");
            id = (node != null) ? node.getNodeValue().toLowerCase() : null;

            node = pNode.getAttributes().getNamedItem("class");
            className = (node != null) ? node.getNodeValue().toLowerCase() : null;
        }

        String typeAndId = type + "#" + id;
        String typeAndClass = type + "." + className;

        // check if the given element is in whitelist: either only the element type, or type and id or type and class
        boolean inList = false;
        if (type != null && Arrays.binarySearch(this.whitelist, type) >= 0)
            inList = true;
        else if (type != null && id != null && Arrays.binarySearch(this.whitelist, typeAndId) >= 0)
            inList = true;
        else if (type != null && className != null && Arrays.binarySearch(this.whitelist, typeAndClass) >= 0)
            inList = true;

        if (LOG.isTraceEnabled())
            LOG.trace("In whitelist: " + inList + " (" + type + " or " + typeAndId + " or " + typeAndClass + ")");

        if (inList)
        {
            // can't remove this node, but we can strip it
            if (LOG.isTraceEnabled())
                LOG.trace("Using " + type + (id != null ? "#" + id : (className != null ? "." + className : "")));
            newNode.appendChild(pNode.cloneNode(true));
            wasStripped = true;
        }

        if (!wasStripped)
        {
            // process the children recursively
            NodeList children = pNode.getChildNodes();
            if (children != null)
            {
                int len = children.getLength();
                for (int i = 0; i < len; i++)
                {
                    whitelisting(children.item(i), newNode);
                }
            }
        }
    }

    /**
     * copied from {@link org.apache.nutch.parse.html.DOMContentUtils}
     */
    private boolean getText(StringBuffer sb, Node node)
    {
        boolean abort = false;
        NodeWalker walker = new NodeWalker(node);
        
        while (walker.hasNext()) {
        
          Node currentNode = walker.nextNode();
          String nodeName = currentNode.getNodeName();
          short nodeType = currentNode.getNodeType();
          
          if ("script".equalsIgnoreCase(nodeName)) {
            walker.skipChildren();
          }
          if ("style".equalsIgnoreCase(nodeName)) {
            walker.skipChildren();
          }
          if (nodeType == Node.COMMENT_NODE) {
            walker.skipChildren();
          }
          if (nodeType == Node.TEXT_NODE) {
            // cleanup and trim the value
            String text = currentNode.getNodeValue();
            text = text.replaceAll("\\s+", " ");
            text = text.trim();
            if (text.length() > 0) {
              if (sb.length() > 0) sb.append(' ');
                sb.append(text);
            }
          }
        }
        
        return abort;
    }

    public void setConf(Configuration conf)
    {
        this.conf = conf;
        // parse configuration for blacklist
        this.blacklist = null;
        String elementsToExclude = getConf().get("parser.html.blacklist", null);
        if ((elementsToExclude != null) && (elementsToExclude.trim().length() > 0))
        {
            elementsToExclude = elementsToExclude.toLowerCase(); // convert to lower case so that there's no case
                                                                 // problems
            LOG.info("Configured using [parser.html.blacklist] to ignore elements [" + elementsToExclude + "]...");
            this.blacklist = elementsToExclude.split(",");
            Arrays.sort(this.blacklist); // required for binary search
        }

        // parse configuration for whitelist
        this.whitelist = null;
        String elementsToInclude = getConf().get("parser.html.whitelist", null);
        if ((elementsToInclude != null) && (elementsToInclude.trim().length() > 0))
        {
            elementsToInclude = elementsToInclude.toLowerCase(); // convert to lower case so that there's no case
                                                                 // problems
            LOG.info("Configured using [parser.html.whitelist] to only use elements [" + elementsToInclude + "]...");
            this.whitelist = elementsToInclude.split(",");
            Arrays.sort(this.whitelist); // required for binary search
        }
    }

    public Configuration getConf()
    {
        return this.conf;
    }

}
