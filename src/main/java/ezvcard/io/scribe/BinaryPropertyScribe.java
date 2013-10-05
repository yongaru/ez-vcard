package ezvcard.io.scribe;

import java.util.List;

import ezvcard.VCard;
import ezvcard.VCardDataType;
import ezvcard.VCardVersion;
import ezvcard.io.CannotParseException;
import ezvcard.io.html.HCardElement;
import ezvcard.io.json.JCardValue;
import ezvcard.io.xml.XCardElement;
import ezvcard.parameter.EncodingParameter;
import ezvcard.parameter.MediaTypeParameter;
import ezvcard.parameter.VCardSubTypes;
import ezvcard.property.BinaryProperty;
import ezvcard.util.DataUri;
import ezvcard.util.org.apache.commons.codec.binary.Base64;

/*
 Copyright (c) 2013, Michael Angstadt
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
 */

/**
 * Marshals properties that have binary data.
 * @author Michael Angstadt
 * @param <T> the property class
 * @param <U> the media type class
 */
public abstract class BinaryPropertyScribe<T extends BinaryProperty<U>, U extends MediaTypeParameter> extends VCardPropertyScribe<T> {
	public BinaryPropertyScribe(Class<T> clazz, String propertyName) {
		super(clazz, propertyName);
	}

	@Override
	protected VCardDataType _defaultDataType(VCardVersion version) {
		switch (version) {
		case V2_1:
		case V3_0:
			return null;
		case V4_0:
			return VCardDataType.URI;
		}
		return null;
	}

	@Override
	protected VCardDataType _dataType(T property, VCardVersion version) {
		if (property.getUrl() != null) {
			switch (version) {
			case V2_1:
				return VCardDataType.URL;
			case V3_0:
			case V4_0:
				return VCardDataType.URI;
			}
		}

		if (property.getData() != null) {
			switch (version) {
			case V2_1:
			case V3_0:
				return null;
			case V4_0:
				return VCardDataType.URI;
			}
		}

		return _defaultDataType(version);
	}

	@Override
	protected void _prepareParameters(T property, VCardSubTypes copy, VCardVersion version, VCard vcard) {
		MediaTypeParameter contentType = property.getContentType();
		if (contentType == null) {
			contentType = new MediaTypeParameter(null, null, null);
		}

		if (property.getUrl() != null) {
			copy.setEncoding(null);

			switch (version) {
			case V2_1:
				copy.setType(contentType.getValue());
				copy.setMediaType(null);
				return;
			case V3_0:
				copy.setType(contentType.getValue());
				copy.setMediaType(null);
				return;
			case V4_0:
				copy.setMediaType(contentType.getMediaType());
				return;
			}
		}

		if (property.getData() != null) {
			copy.setMediaType(null);

			switch (version) {
			case V2_1:
				copy.setEncoding(EncodingParameter.BASE64);
				copy.setType(contentType.getValue());
				return;
			case V3_0:
				copy.setEncoding(EncodingParameter.B);
				copy.setType(contentType.getValue());
				return;
			case V4_0:
				copy.setEncoding(null);
				//don't null out TYPE, it could be set to "home", "work", etc
				return;
			}
		}
	}

	@Override
	protected String _writeText(T property, VCardVersion version) {
		return write(property, version);
	}

	@Override
	protected T _parseText(String value, VCardDataType dataType, VCardVersion version, VCardSubTypes parameters, List<String> warnings) {
		value = unescape(value);
		return parse(value, dataType, parameters, version, warnings);
	}

	@Override
	protected void _writeXml(T property, XCardElement parent) {
		parent.append(VCardDataType.URI, write(property, parent.version()));
	}

	@Override
	protected T _parseXml(XCardElement element, VCardSubTypes parameters, List<String> warnings) {
		String value = element.first(VCardDataType.URI);
		if (value != null) {
			return parse(value, VCardDataType.URI, parameters, element.version(), warnings);
		}

		throw missingXmlElements(VCardDataType.URI);
	}

	@Override
	protected T _parseHtml(HCardElement element, List<String> warnings) {
		String elementName = element.tagName();
		if (!"object".equals(elementName)) {
			throw new CannotParseException("Cannot parse <" + elementName + "> tag (<object> tag expected).");
		}

		String data = element.absUrl("data");
		if (data.length() == 0) {
			throw new CannotParseException("<object> tag does not have a \"data\" attribute.");
		}

		try {
			DataUri uri = new DataUri(data);
			U mediaType = _buildMediaTypeObj(uri.getContentType());

			return _newInstance(uri.getData(), mediaType);
		} catch (IllegalArgumentException e) {
			//not a data URI
			U mediaType = null;
			String type = element.attr("type");
			if (type.length() > 0) {
				mediaType = _buildMediaTypeObj(type);
			}

			return _newInstance(data, mediaType);
		}
	}

	@Override
	protected JCardValue _writeJson(T property) {
		return JCardValue.single(write(property, VCardVersion.V4_0));
	}

	@Override
	protected T _parseJson(JCardValue value, VCardDataType dataType, VCardSubTypes parameters, List<String> warnings) {
		String valueStr = value.asSingle();
		return parse(valueStr, dataType, parameters, VCardVersion.V4_0, warnings);
	}

	/**
	 * Called if the unmarshalling code cannot determine how to unmarshal the
	 * value.
	 * @param value the value
	 * @param version the version of the vCard
	 * @param warnings the warnings
	 * @param contentType the content type of the resource of null if unknown
	 */
	protected T cannotUnmarshalValue(String value, VCardVersion version, List<String> warnings, U contentType) {
		switch (version) {
		case V2_1:
		case V3_0:
			if (value.startsWith("http")) {
				return _newInstance(value, contentType);
			}
			return _newInstance(Base64.decodeBase64(value), contentType);
		case V4_0:
			return _newInstance(value, contentType);
		}
		return null;
	}

	/**
	 * Builds a {@link MediaTypeParameter} object based on the information in
	 * the MEDIATYPE parameter or data URI of 4.0 vCards.
	 * @param mediaType the media type string (e.g. "image/jpeg")
	 * @return the parameter object
	 */
	protected abstract U _buildMediaTypeObj(String mediaType);

	/**
	 * Builds a {@link MediaTypeParameter} object based on the value of the TYPE
	 * parameter in 2.1/3.0 vCards.
	 * @param type the TYPE value
	 * @return the parameter object
	 */
	protected abstract U _buildTypeObj(String type);

	protected abstract T _newInstance(String uri, U contentType);

	protected abstract T _newInstance(byte data[], U contentType);

	protected U parseContentType(VCardSubTypes parameters, VCardVersion version) {
		switch (version) {
		case V2_1:
		case V3_0:
			//get the TYPE parameter
			String type = parameters.getType();
			if (type != null) {
				return _buildTypeObj(type);
			}
			break;
		case V4_0:
			//get the MEDIATYPE parameter
			String mediaType = parameters.getMediaType();
			if (mediaType != null) {
				return _buildMediaTypeObj(mediaType);
			}
			break;
		}
		return null;
	}

	private T parse(String value, VCardDataType dataType, VCardSubTypes parameters, VCardVersion version, List<String> warnings) {
		U contentType = parseContentType(parameters, version);

		switch (version) {
		case V2_1:
		case V3_0:
			//parse as URL
			if (dataType == VCardDataType.URL || dataType == VCardDataType.URI) {
				return _newInstance(value, contentType);
			}

			//parse as binary
			EncodingParameter encodingSubType = parameters.getEncoding();
			if (encodingSubType == EncodingParameter.BASE64 || encodingSubType == EncodingParameter.B) {
				return _newInstance(Base64.decodeBase64(value), contentType);
			}

			break;
		case V4_0:
			try {
				//parse as data URI
				DataUri uri = new DataUri(value);
				contentType = _buildMediaTypeObj(uri.getContentType());
				return _newInstance(uri.getData(), contentType);
			} catch (IllegalArgumentException e) {
				//not a data URI
			}
			break;
		}

		return cannotUnmarshalValue(value, version, warnings, contentType);
	}

	private String write(T property, VCardVersion version) {
		String url = property.getUrl();
		if (url != null) {
			return url;
		}

		byte data[] = property.getData();
		if (data != null) {
			switch (version) {
			case V2_1:
			case V3_0:
				return new String(Base64.encodeBase64(data));
			case V4_0:
				U contentType = property.getContentType();
				String mediaType = (contentType == null || contentType.getMediaType() == null) ? "application/octet-stream" : contentType.getMediaType();
				return new DataUri(mediaType, data).toString();
			}
		}

		return "";
	}
}