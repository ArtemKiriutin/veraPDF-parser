package org.verapdf.parser;

import org.verapdf.as.ASAtom;
import org.verapdf.as.exceptions.StringExceptions;
import org.verapdf.as.io.ASInputStream;
import org.verapdf.cos.*;
import org.verapdf.io.SeekableInputStream;
import org.verapdf.pd.encryption.StandardSecurityHandler;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Timur Kamalov
 */
public class COSParser extends BaseParser {

	private static final Logger LOGGER = Logger.getLogger(COSParser.class.getCanonicalName());

	/**
	 * Linearization dictionary must be in first 1024 bytes of document
	 */
	protected final int LINEARIZATION_DICTIONARY_LOOKUP_SIZE = 1024;

	protected COSDocument document;
	protected Queue<COSObject> objects = new LinkedList<>();
	protected Queue<Long> integers = new LinkedList<>();
    protected COSKey keyOfCurrentObject;

	protected boolean flag = true;

	public COSParser(final SeekableInputStream seekableInputStream) throws IOException {
		super(seekableInputStream);
	}

	public COSParser(final String filename) throws IOException {
		super(filename);
	}

	public COSParser(final InputStream fileStream) throws IOException {
		super(fileStream);
	}

	public COSParser(ASInputStream asInputStream) throws IOException {
		super(asInputStream);
	}

	public COSParser(final COSDocument document, final String filename) throws IOException { //tmp ??
		this(filename);
		this.document = document;
	}

	public COSParser(final COSDocument document, final InputStream fileStream) throws IOException { //tmp ??
		this(fileStream);
		this.document = document;
	}

	public COSObject nextObject() throws IOException {
		if (!this.objects.isEmpty()) {
			COSObject result = this.objects.peek();
			this.objects.remove();
			return result;
		}

		if (this.flag) {
			initializeToken();
			nextToken();
		}
		this.flag = true;

		final Token token = getToken();

		if (token.type == Token.Type.TT_INTEGER) {  // looking for indirect reference
			this.integers.add(Long.valueOf(token.integer));
			if (this.integers.size() == 3) {
				COSObject result = COSInteger.construct(this.integers.peek().longValue());
				this.integers.remove();
				return result;
			}
			return nextObject();
		}

		if (token.type == Token.Type.TT_KEYWORD
				&& token.keyword == Token.Keyword.KW_R
				&& this.integers.size() == 2) {
			final int number = this.integers.peek().intValue();
			this.integers.remove();
			final int generation = this.integers.peek().intValue();
			this.integers.remove();
			return COSIndirect.construct(new COSKey(number, generation), document);
		}

		if (!this.integers.isEmpty()) {
			COSObject result = COSInteger.construct(this.integers.peek().longValue());
			this.integers.remove();
			while (!this.integers.isEmpty()) {
				this.objects.add(COSInteger.construct(this.integers.peek().longValue()));
				this.integers.remove();
			}
			this.flag = false;
			return result;
		}

		switch (token.type) {
			case TT_NONE:
				break;
			case TT_KEYWORD: {
				switch (token.keyword) {
					case KW_NONE:
						break;
					case KW_NULL:
						return COSNull.construct();
					case KW_TRUE:
						return COSBoolean.construct(true);
					case KW_FALSE:
						return COSBoolean.construct(false);
					case KW_STREAM:
					case KW_ENDSTREAM:
					case KW_OBJ:
					case KW_ENDOBJ:
					case KW_R:
					case KW_N:
					case KW_F:
					case KW_XREF:
					case KW_STARTXREF:
					case KW_TRAILER:
						break;
				}
				break;
			}
			case TT_INTEGER: //should not enter here
				break;
			case TT_REAL:
				return COSReal.construct(token.real);
			case TT_LITSTRING:
				return COSString.construct(token.getByteValue());
			case TT_HEXSTRING:
				COSObject res = COSString.construct(token.getByteValue(), true,
						token.getHexCount().longValue(), token.isContainsOnlyHex());
				if(this.document == null || !this.document.isEncrypted()) {
					return res;
				}
			return this.decryptCOSString(res);
			case TT_NAME:
				return COSName.construct(token.getValue());
			case TT_OPENARRAY:
				this.flag = false;
				return getArray();
			case TT_CLOSEARRAY:
				return new COSObject();
			case TT_OPENDICT:
				this.flag = false;
				return getDictionary();
			case TT_CLOSEDICT:
				return new COSObject();
			case TT_EOF:
				return new COSObject();
		}
		return new COSObject();
	}

	protected COSObject getArray() throws IOException {
		if (this.flag) {
			nextToken();
		}
		this.flag = true;

		final Token token = getToken();
		if (token.type != Token.Type.TT_OPENARRAY) {
			return new COSObject();
		}

		COSObject arr = COSArray.construct();

		COSObject obj = nextObject();
		while(!obj.empty()) {
			arr.add(obj);
			obj = nextObject();
		}

		if (token.type != Token.Type.TT_CLOSEARRAY) {
			closeInputStream();
			// TODO : replace with ASException
			throw new IOException("PDFParser::GetArray()" + StringExceptions.INVALID_PDF_ARRAY);
		}

		return arr;
	}

	protected COSObject getName() throws IOException {
		if (this.flag) {
			nextToken();
		}
		this.flag = true;

		final Token token = getToken();
		if (token.type != Token.Type.TT_NAME) {
			return new COSObject();
		}
		return COSName.construct(token.getValue());
	}

	protected COSObject getDictionary() throws IOException {
		if (this.flag) {
			nextToken();
		}
		this.flag = true;
		final Token token = getToken();

		if (token.type != Token.Type.TT_OPENDICT) {
			return new COSObject();
		}

		COSObject dict = COSDictionary.construct();

		COSObject key = getName();
		while (!key.empty()) {
			COSObject obj = nextObject();
			dict.setKey(key.getName(), obj);
			key = getName();
		}

		if (token.type != Token.Type.TT_CLOSEDICT) {
			closeInputStream();
			// TODO : replace with ASException
			throw new IOException("PDFParser::GetDictionary()" + StringExceptions.INVALID_PDF_DICTONARY);
		}

		long reset = this.source.getOffset();
		if (this.flag) {
			nextToken();
		}
		this.flag = false;

		if (token.type == Token.Type.TT_KEYWORD &&
				token.keyword == Token.Keyword.KW_STREAM) {
			return getStream(dict);
		}
		this.source.seek(reset);
		this.flag = true;

		return dict;
	}

	protected COSObject getStream(COSObject dict) throws IOException {
		if (this.flag) {
			nextToken();
		}
		this.flag = true;

		final Token token = getToken();

		if (token.type != Token.Type.TT_KEYWORD ||
				token.keyword != Token.Keyword.KW_STREAM) {
			this.flag = false;
			return dict;
		}

		checkStreamSpacings(dict);
		long streamStartOffset = source.getOffset();

		skipStreamSpaces();

		long size = dict.getKey(ASAtom.LENGTH).getInteger().longValue();
		source.seek(streamStartOffset);

		boolean streamLengthValid = checkStreamLength(size);

		if (streamLengthValid) {
			dict.setRealStreamSize(size);
			ASInputStream stm = super.getRandomAccess(size);
			dict.setData(stm);
		} else {
			//trying to find endstream keyword
			long realStreamSize = -1;
			int bufferLength = (int) (size > 512 ? 512 : size);
			byte[] buffer = new byte[bufferLength];
			while (!source.isEOF()) {
				long bytesRead = source.read(buffer, bufferLength);
				for (int i = 0; i < bytesRead; i++) {
					if (buffer[i] == 101) {
						long reset = source.getOffset();
						long possibleEndstreamOffset = reset - bytesRead + i;
						source.seek(possibleEndstreamOffset);
						nextToken();
						if (token.type == Token.Type.TT_KEYWORD &&
								token.keyword == Token.Keyword.KW_ENDSTREAM) {
							realStreamSize = possibleEndstreamOffset - streamStartOffset;
							dict.setRealStreamSize(realStreamSize);
							ASInputStream stm = super.getRandomAccess(realStreamSize);
							dict.setData(stm);
							source.seek(possibleEndstreamOffset);
							break;
						}
						source.seek(reset);
					}
				}
				if (realStreamSize != -1) {
					break;
				}
			}
			if (realStreamSize == -1) {
				//TODO : exception?
			}
		}

		checkEndstreamSpacings(dict, streamStartOffset, size);

		try {
			if (this.document.isEncrypted()) {
				this.document.getStandardSecurityHandler().decryptStream(
						(COSStream) dict.getDirectBase(), this.keyOfCurrentObject);
			}
		} catch (GeneralSecurityException e) {
			throw new IOException("Stream " + this.keyOfCurrentObject + " cannot be decrypted", e);
		}
		return dict;
	}


	private void checkStreamSpacings(COSObject stream) throws IOException {
		byte whiteSpace = source.readByte();
		if (whiteSpace == 13) {
			whiteSpace = source.readByte();
			if (whiteSpace != 10) {
				stream.setStreamKeywordCRLFCompliant(false);
				source.unread();
			}
		} else if (whiteSpace != 10) {
			LOGGER.log(Level.WARNING, "Stream at " + source.getOffset() + " offset has no EOL marker.");
			stream.setStreamKeywordCRLFCompliant(false);
			source.unread();
		}
	}

	private boolean checkStreamLength(long streamLength) throws IOException {
		boolean validLength = true;
		long start = source.getOffset();
		long expectedEndstreamOffset = start + streamLength;
		if (expectedEndstreamOffset > source.getStreamLength()) {
			validLength = false;
			LOGGER.log(Level.WARNING, "Couldn't find expected endstream keyword at offset " + expectedEndstreamOffset);
		} else {
			source.seek(expectedEndstreamOffset);

			nextToken();
			final Token token = getToken();
			if (token.type != Token.Type.TT_KEYWORD ||
					token.keyword != Token.Keyword.KW_ENDSTREAM) {
				validLength = false;
				LOGGER.log(Level.WARNING, "Couldn't find expected endstream keyword at offset " + expectedEndstreamOffset);
			}

			source.seek(start);
		}
		return validLength;
	}

	private void checkEndstreamSpacings(COSObject stream, long streamStartOffset, long expectedLength) throws IOException {
		skipSpaces();

		byte eolCount = 0;
		long approximateLength = source.getOffset() - streamStartOffset;
		long diff = approximateLength - expectedLength;

		source.unread(2);
		int firstSymbol = source.readByte();
		int secondSymbol = source.readByte();
		if (secondSymbol == 10) {
			if (firstSymbol == 13) {
				eolCount = (byte) (diff == 1 ? 1 : 2);
			} else {
				eolCount = 1;
			}
		} else if (secondSymbol == 13) {
			eolCount = 1;
		} else {
			LOGGER.log(Level.WARNING, "End of stream at " + source.getOffset() + " offset doesn't contain EOL marker.");
			stream.setEndstreamKeywordCRLFCompliant(false);
		}

		stream.setRealStreamSize(approximateLength - eolCount);
		nextToken();
	}

	public COSDocument getDocument() {
		return document;
	}

	private COSObject decryptCOSString(COSObject string) {
		StandardSecurityHandler ssh =
				this.document.getStandardSecurityHandler();
        try {
            ssh.decryptString((COSString) string.getDirectBase(), this.keyOfCurrentObject);
            return string;
        } catch (IOException | GeneralSecurityException e) {
            LOGGER.log(Level.WARNING, "Can't decrypt string in object " + this.keyOfCurrentObject);
            return string;
        }
	}
}
