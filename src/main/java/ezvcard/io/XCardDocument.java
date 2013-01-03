package ezvcard.io;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardSubTypes;
import ezvcard.VCardVersion;
import ezvcard.types.MemberType;
import ezvcard.types.ProdIdType;
import ezvcard.types.VCardType;
import ezvcard.util.IOUtils;
import ezvcard.util.ListMultimap;

/*
 Copyright (c) 2012, Michael Angstadt
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met: 

 1. Redistributions of source code must retain the above copyright notice, this
 list of conditions and the following disclaimer. 
 2. Redistributions in binary form must reproduce the above copyright notice,
 this list of conditions and the following disclaimer in the documentation
 and/or other materials provided with the distribution. 

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 The views and conclusions contained in the software and documentation are those
 of the authors and should not be interpreted as representing official policies, 
 either expressed or implied, of the FreeBSD Project.
 */

/**
 * Converts vCards to their XML representation.
 * @author Michael Angstadt
 * @see <a href="http://tools.ietf.org/html/rfc6351">RFC 6351</a>
 */
public class XCardDocument {
	/**
	 * Defines the names of the XML elements that are used to hold each
	 * parameter's value.
	 */
	private static final Map<String, String> parameterChildElementNames;
	static {
		Map<String, String> m = new HashMap<String, String>();
		m.put("altid", "text");
		m.put("calscale", "text");
		m.put("geo", "uri");
		m.put("label", "text");
		m.put("language", "language-tag");
		m.put("mediatype", "text");
		m.put("pid", "text");
		m.put("pref", "integer");
		m.put("sort-as", "text");
		m.put("type", "text");
		m.put("tz", "uri");
		parameterChildElementNames = Collections.unmodifiableMap(m);
	}

	private CompatibilityMode compatibilityMode;
	private boolean addProdId = true;
	private VCardVersion targetVersion = VCardVersion.V4_0; //xCard standard only supports 4.0
	private List<String> warnings = new ArrayList<String>();
	private final Document document;
	private final Element root;

	public XCardDocument() {
		this(CompatibilityMode.RFC);
	}

	/**
	 * @param compatibilityMode the compatibility mode
	 */
	public XCardDocument(CompatibilityMode compatibilityMode) {
		this.compatibilityMode = compatibilityMode;

		DocumentBuilder builder = null;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			builder = factory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			//should never be thrown
		}
		document = builder.newDocument();
		root = createElement("vcards");
		document.appendChild(root);
	}

	/**
	 * Gets the compatibility mode. Used for customizing the marshalling process
	 * to target a particular application.
	 * @return the compatibility mode
	 */
	public CompatibilityMode getCompatibilityMode() {
		return compatibilityMode;
	}

	/**
	 * Sets the compatibility mode. Used for customizing the marshalling process
	 * to target a particular application.
	 * @param compatibilityMode the compatibility mode
	 */
	public void setCompatibilityMode(CompatibilityMode compatibilityMode) {
		this.compatibilityMode = compatibilityMode;
	}

	/**
	 * Gets whether or not a "PRODID" type will be added to each vCard, saying
	 * that the vCard was generated by this library.
	 * @return true if it will be added, false if not (defaults to true)
	 */
	public boolean isAddProdId() {
		return addProdId;
	}

	/**
	 * Sets whether or not to add a "PRODID" type to each vCard, saying that the
	 * vCard was generated by this library.
	 * @param addProdId true to add this type, false not to (defaults to true)
	 */
	public void setAddProdId(boolean addProdId) {
		this.addProdId = addProdId;
	}

	/**
	 * Gets the warnings from the last vCard that was marshalled. This list is
	 * reset every time a new vCard is written.
	 * @return the warnings or empty list if there were no warnings
	 */
	public List<String> getWarnings() {
		return new ArrayList<String>(warnings);
	}

	/**
	 * Gets the XML document that was generated.
	 * @return the XML document
	 */
	public Document getDocument() {
		return document;
	}

	/**
	 * Writes the XML document to a string without pretty-printing it.
	 * @return the XML string
	 */
	public String write() {
		return write(-1);
	}

	/**
	 * Writes the XML document to a string and pretty-prints it.
	 * @param indent the number of indent spaces to use for pretty-printing
	 * @return the XML string
	 */
	public String write(int indent) {
		StringWriter sw = new StringWriter();
		try {
			write(sw, indent);
		} catch (TransformerException e) {
			//writing to string
		}
		return sw.toString();
	}

	/**
	 * Writes the XML document to an output stream without pretty-printing it.
	 * @param out the output stream
	 * @throws TransformerException if there's a problem writing to the output
	 * stream
	 */
	public void write(OutputStream out) throws TransformerException {
		write(out, -1);
	}

	/**
	 * Writes the XML document to an output stream and pretty-prints it.
	 * @param out the output stream
	 * @param indent the number of indent spaces to use for pretty-printing
	 * @throws TransformerException if there's a problem writing to the output
	 * stream
	 */
	public void write(OutputStream out, int indent) throws TransformerException {
		write(new OutputStreamWriter(out), indent);
	}

	/**
	 * Writes the XML document to a file without pretty-printing it.
	 * @param file the file
	 * @throws TransformerException if there's a problem writing to the file
	 */
	public void write(File file) throws TransformerException, IOException {
		write(file, -1);
	}

	/**
	 * Writes the XML document to a file and pretty-prints it.
	 * @param file the file stream
	 * @param indent the number of indent spaces to use for pretty-printing
	 * @throws TransformerException if there's a problem writing to the file
	 */
	public void write(File file, int indent) throws TransformerException, IOException {
		FileWriter writer = null;
		try {
			writer = new FileWriter(file);
			write(writer, indent);
		} finally {
			IOUtils.closeQuietly(writer);
		}
	}

	/**
	 * Writes the XML document to a writer without pretty-printing it.
	 * @param writer the writer
	 * @throws TransformerException if there's a problem writing to the writer
	 */
	public void write(Writer writer) throws TransformerException {
		write(writer, -1);
	}

	/**
	 * Writes the XML document to a writer and pretty-prints it.
	 * @param writer the writer
	 * @param indent the number of indent spaces to use for pretty-printing
	 * @throws TransformerException if there's a problem writing to the writer
	 */
	public void write(Writer writer, int indent) throws TransformerException {
		Transformer t = TransformerFactory.newInstance().newTransformer();
		Source source = new DOMSource(document);
		Result result = new StreamResult(writer);
		if (indent >= 0) {
			t.setOutputProperty(OutputKeys.INDENT, "yes");
			try {
				t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", indent + "");
			} catch (IllegalArgumentException e) {
				//in-case this property is not supported on other systems
			}
		}
		t.transform(source, result);
	}

	/**
	 * Adds a vCard to the XML document
	 * @param vcard the vCard to add
	 */
	public void addVCard(VCard vcard) {
		warnings.clear();

		if (vcard.getFormattedName() == null) {
			warnings.add("vCard version " + targetVersion + " requires that a formatted name be defined.");
		}

		ListMultimap<String, VCardType> typesToAdd = new ListMultimap<String, VCardType>(); //group the types by group name (null = no group name)

		for (VCardType type : vcard.getAllTypes()) {
			if (addProdId && type instanceof ProdIdType) {
				//do not add the PRODID in the vCard if "addProdId" is true
				return;
			}

			//determine if this type is supported by the target version
			if (!supportsTargetVersion(type)) {
				warnings.add("The " + type.getTypeName() + " type is not supported by xCard (vCard version " + targetVersion + ") and will not be added to the xCard.  Supported versions are " + Arrays.toString(type.getSupportedVersions()));
				continue;
			}

			//check for correct KIND value if there are MEMBER types
			if (type instanceof MemberType && (vcard.getKind() == null || !vcard.getKind().isGroup())) {
				warnings.add("The value of KIND must be set to \"group\" in order to add MEMBERs to the vCard.");
				continue;
			}

			typesToAdd.put(type.getGroup(), type);
		}

		//add an extended type saying it was generated by this library
		if (addProdId) {
			ProdIdType prodId = new ProdIdType("ez-vcard " + Ezvcard.VERSION);
			typesToAdd.put(prodId.getGroup(), prodId);
		}

		//marshal each type object
		Element vcardElement = createElement("vcard");
		for (String groupName : typesToAdd.keySet()) {
			Element parent;
			if (groupName != null) {
				Element groupElement = createElement("group");
				groupElement.setAttribute("name", groupName);
				vcardElement.appendChild(groupElement);
				parent = groupElement;
			} else {
				parent = vcardElement;
			}

			List<String> warningsBuf = new ArrayList<String>();
			for (VCardType type : typesToAdd.get(groupName)) {
				warningsBuf.clear();
				try {
					Element typeElement = marshalType(type, vcard, warningsBuf);
					parent.appendChild(typeElement);
				} catch (SkipMeException e) {
					warningsBuf.add(type.getTypeName() + " property will not be marshalled: " + e.getMessage());
				} catch (EmbeddedVCardException e) {
					warningsBuf.add(type.getTypeName() + " property will not be marshalled: xCard does not supported embedded vCards.");
				} finally {
					warnings.addAll(warningsBuf);
				}
			}
		}
		root.appendChild(vcardElement);
	}

	/**
	 * Determines if a type supports the target version.
	 * @param type the type
	 * @return true if it supports the target version, false if not
	 */
	private boolean supportsTargetVersion(VCardType type) {
		for (VCardVersion version : type.getSupportedVersions()) {
			if (version == targetVersion) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Marshals a type object to an XML element.
	 * @param type the type object to marshal
	 * @param vcard the vcard the type belongs to
	 * @param warningsBuf the list to add the warnings to
	 * @return the XML element or null not to add anything to the final XML
	 * document
	 */
	private Element marshalType(VCardType type, VCard vcard, List<String> warningsBuf) {
		QName qname = type.getQName();
		String ns, localPart;
		if (qname == null) {
			localPart = type.getTypeName().toLowerCase();
			ns = targetVersion.getXmlNamespace();
		} else {
			localPart = qname.getLocalPart();
			ns = qname.getNamespaceURI();
		}
		Element typeElement = createElement(localPart, ns);

		//marshal the sub types
		VCardSubTypes subTypes = type.marshalSubTypes(targetVersion, warningsBuf, compatibilityMode, vcard);
		subTypes.setValue(null); //don't include the VALUE parameter (modification of the "VCardSubTypes" object is safe because it's a copy)
		if (!subTypes.getMultimap().isEmpty()) {
			Element parametersElement = createElement("parameters");
			for (String paramName : subTypes.getNames()) {
				Element parameterElement = createElement(paramName.toLowerCase());
				for (String paramValue : subTypes.get(paramName)) {
					String valueElementName = parameterChildElementNames.get(paramName.toLowerCase());
					if (valueElementName == null) {
						valueElementName = "unknown";
					}
					Element parameterValueElement = createElement(valueElementName);
					parameterValueElement.setTextContent(paramValue);
					parameterElement.appendChild(parameterValueElement);
				}
				parametersElement.appendChild(parameterElement);
			}
			typeElement.appendChild(parametersElement);
		}

		//marshal the value
		type.marshalValue(typeElement, targetVersion, warningsBuf, compatibilityMode);

		return typeElement;
	}

	/**
	 * Creates a new XML element.
	 * @param name the name of the XML element
	 * @return the new XML element
	 */
	private Element createElement(String name) {
		return createElement(name, targetVersion.getXmlNamespace());
	}

	/**
	 * Creates a new XML element.
	 * @param name the name of the XML element
	 * @param ns the namespace of the XML element
	 * @return the new XML element
	 */
	private Element createElement(String name, String ns) {
		return document.createElementNS(ns, name);
	}
}
