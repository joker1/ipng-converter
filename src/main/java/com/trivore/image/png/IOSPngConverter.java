package com.trivore.image.png;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.Inflater;
import com.jcraft.jzlib.JZlib;

/**
 * @author Miika Vesti <miika.vesti@trivore.com>
 */
public class IOSPngConverter {
	
	static final byte[] PNG_HEADER = {-119, 80, 78, 71, 13, 10, 26, 10};
	
	private final DataInputStream source;
	
	private final boolean closeInput;
	
	public IOSPngConverter(InputStream source) {
		this(source, true);
	}
	
	public IOSPngConverter(InputStream source, boolean closeInput) {
		super();
		Objects.requireNonNull(source, "iOS png source must not be null");
		this.source = new DataInputStream(source);
		this.closeInput = closeInput;
	}
	
	public IOSPngConverter(byte[] source) {
		this(new ByteArrayInputStream(source));
	}
	
	public IOSPngConverter(byte[] source, int offset, int length) {
		this(new ByteArrayInputStream(source, offset, length));
	}
	
	public IOSPngConverter(File source) throws FileNotFoundException {
		this(new FileInputStream(source));
	}
	
	public void convert(OutputStream target) throws IOException {
		convert(target, true);
	}
	
	public void convert(OutputStream target, boolean closeOutput) throws IOException {
		List<PNGTrunk> trunks = new ArrayList<>();
		boolean bWithCgBI = false;
		byte[] nPNGHeader = new byte[8];
		PNGTrunk firstDataTrunk = null;
		PNGIHDRTrunk ihdrTrunk = null;
		try {
			/* Read PNG header. */
			source.readFully(nPNGHeader);
			
			/* Make sure PNG header is valid. */
			if (!Arrays.equals(PNG_HEADER, nPNGHeader)) {
				throw new IOException("Invalid PNG header: " + Arrays.toString(nPNGHeader));
			}
			
			/* Read PNG trunks. */
			PNGTrunk trunk;
			do {
				trunk = PNGTrunk.generateTrunk(source);
				if (trunk.getName().equalsIgnoreCase("CgBI")) {
					/* Skip Apple-specific CgBI trunk. */
					bWithCgBI = true;
					continue;
				} else if (trunk.getName().equalsIgnoreCase("IHDR")) {
					/* Save IHDR trunk. */
					ihdrTrunk = (PNGIHDRTrunk) trunk;
				} else if (trunk.getName().equals("IDAT") && firstDataTrunk == null) {
					/* Save first IDAT trunk. */
					firstDataTrunk = trunk;
				}
				trunks.add(trunk);
			} while (!trunk.getName().equalsIgnoreCase("IEND")); /* PNG ends with IEND trunk */
		} catch (IOException ioe) {
			if (closeOutput) {
				/* Close output if required before re-throwing the exception. */
				target.close();
			}
			throw ioe;
		} finally {
			if (closeInput) {
				/* Close input if required. */
				source.close();
			}
		}
		
		try {
			target.write(nPNGHeader);
			if (bWithCgBI) {
				/* CgBI header found -> Convert to normal PNG image. */
				PNGTrunk dataTrunk = convertData(trunks, ihdrTrunk, firstDataTrunk);
				boolean dataWritten = false;
				for (PNGTrunk trunk : trunks) {
					if (trunk.getName().equalsIgnoreCase("IDAT")) {
						if (dataWritten) {
							continue;
						}
						dataTrunk.writeToStream(target);
						dataWritten = true;
					} else {
						trunk.writeToStream(target);
					}
				}
			} else {
				/* CgBI header not found -> Normal PNG image -> Copy as-is. */
				for (PNGTrunk trunk : trunks) {
					trunk.writeToStream(target);
				}
			}
		} finally {
			if (closeOutput) {
				/* Close output if required. */
				target.close();
			}
		}
	}
	
	public byte[] convertBytes() throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		convert(bout);
		bout.close();
		return bout.toByteArray();
	}
	
	public void convert(File target) throws IOException {
		FileOutputStream fout = new FileOutputStream(target);
		convert(fout);
		fout.close();
	}
	
	private PNGTrunk convertData(List<PNGTrunk> trunks, PNGIHDRTrunk ihdrTrunk, PNGTrunk firstDataTrunk)
			throws IOException {
		int nMaxInflateBuffer = 4 * (ihdrTrunk.m_nWidth + 1) * ihdrTrunk.m_nHeight;
		byte[] outputBuffer = new byte[nMaxInflateBuffer];
		
		long inflatedSize = inflate(trunks, outputBuffer, nMaxInflateBuffer);
		
		// Switch the color
		int nIndex = 0;
		byte nTemp;
		for (int y = 0; y < ihdrTrunk.m_nHeight; y++) {
			nIndex++;
			for (int x = 0; x < ihdrTrunk.m_nWidth; x++) {
				nTemp = outputBuffer[nIndex];
				outputBuffer[nIndex] = outputBuffer[nIndex + 2];
				outputBuffer[nIndex + 2] = nTemp;
				nIndex += 4;
			}
		}
		
		Deflater deflater = deflate(outputBuffer, (int) inflatedSize, nMaxInflateBuffer);
		
		CRC32 crc32 = new CRC32();
		crc32.update(firstDataTrunk.getName().getBytes());
		crc32.update(deflater.getNextOut(), 0, (int) deflater.getTotalOut());
		long lCRCValue = crc32.getValue();
		
		firstDataTrunk.m_nData = deflater.getNextOut();
		firstDataTrunk.m_nCRC[0] = (byte) ((lCRCValue & 0xFF000000) >> 24);
		firstDataTrunk.m_nCRC[1] = (byte) ((lCRCValue & 0xFF0000) >> 16);
		firstDataTrunk.m_nCRC[2] = (byte) ((lCRCValue & 0xFF00) >> 8);
		firstDataTrunk.m_nCRC[3] = (byte) (lCRCValue & 0xFF);
		firstDataTrunk.m_nSize = (int) deflater.getTotalOut();
		
		return firstDataTrunk;
	}
	
	private long inflate(List<PNGTrunk> trunks, byte[] outputBuffer, int nMaxInflateBuffer) throws GZIPException {
		Inflater inflater = new Inflater(-15);
		
		for (PNGTrunk dataTrunk : trunks) {
			if (!"IDAT".equalsIgnoreCase(dataTrunk.getName())) {
				continue;
			}
			inflater.setInput(dataTrunk.getData(), true);
		}
		
		inflater.setOutput(outputBuffer);
		
		int nResult;
		try {
			nResult = inflater.inflate(JZlib.Z_NO_FLUSH);
			checkResultStatus(nResult);
		} finally {
			inflater.inflateEnd();
		}
		
		if (inflater.getTotalOut() > nMaxInflateBuffer) {
			// log.fine("PNGCONV_ERR_INFLATED_OVER");
		}
		
		return inflater.getTotalOut();
	}
	
	private Deflater deflate(byte[] buffer, int length, int nMaxInflateBuffer) throws GZIPException {
		Deflater deflater = new Deflater();
		deflater.setInput(buffer, 0, length, false);
		
		int nMaxDeflateBuffer = nMaxInflateBuffer + 1024;
		byte[] deBuffer = new byte[nMaxDeflateBuffer];
		deflater.setOutput(deBuffer);
		
		deflater.deflateInit(JZlib.Z_BEST_COMPRESSION);
		int nResult = deflater.deflate(JZlib.Z_FINISH);
		checkResultStatus(nResult);
		
		if (deflater.getTotalOut() > nMaxDeflateBuffer) {
			throw new GZIPException("deflater output buffer was too small");
		}
		
		return deflater;
	}
	
	private void checkResultStatus(int nResult) throws GZIPException {
		switch (nResult) {
			case JZlib.Z_OK:
			case JZlib.Z_STREAM_END:
				break;
				
			case JZlib.Z_NEED_DICT:
				throw new GZIPException("Z_NEED_DICT - " + nResult);
			case JZlib.Z_DATA_ERROR:
				throw new GZIPException("Z_DATA_ERROR - " + nResult);
			case JZlib.Z_MEM_ERROR:
				throw new GZIPException("Z_MEM_ERROR - " + nResult);
			case JZlib.Z_STREAM_ERROR:
				throw new GZIPException("Z_STREAM_ERROR - " + nResult);
			case JZlib.Z_BUF_ERROR:
				throw new GZIPException("Z_BUF_ERROR - " + nResult);
			default:
				throw new GZIPException("inflater error: " + nResult);
		}
	}
}
