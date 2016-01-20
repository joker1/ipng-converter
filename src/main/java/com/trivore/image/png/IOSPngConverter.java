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
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

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
	
	public IOSPngConverter(byte[] sourceBuffer) {
		this(new ByteArrayInputStream(sourceBuffer));
	}
	
	public IOSPngConverter(byte[] sourceBuffer, int offset, int length) {
		this(new ByteArrayInputStream(sourceBuffer, offset, length));
	}
	
	public IOSPngConverter(File sourceFile) throws FileNotFoundException {
		this(new FileInputStream(sourceFile));
	}
	
	public IOSPngConverter(String sourceFilename) throws FileNotFoundException {
		this(new File(sourceFilename));
	}
	
	public void convert(OutputStream target) throws IOException, DataFormatException {
		convert(target, true);
	}
	
	public void convert(OutputStream target, boolean closeOutput) throws IOException, DataFormatException {
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
				throw new DataFormatException("Invalid PNG header: " + Arrays.toString(nPNGHeader));
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
	
	public byte[] convertBytes() throws IOException, DataFormatException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		convert(bout);
		bout.close();
		return bout.toByteArray();
	}
	
	public void convert(File target) throws IOException, DataFormatException {
		FileOutputStream fout = new FileOutputStream(target);
		convert(fout);
		fout.close();
	}
	
	private PNGTrunk convertData(List<PNGTrunk> trunks, PNGIHDRTrunk ihdrTrunk, PNGTrunk firstDataTrunk)
			throws IOException, DataFormatException {
		int nMaxInflateBuffer = 4 * (ihdrTrunk.m_nWidth + 1) * ihdrTrunk.m_nHeight;
		byte[] outputBuffer = new byte[nMaxInflateBuffer];
		
		Inflater inflater = new Inflater(true);
		int offset = 0;
		for (PNGTrunk trunk : trunks) {
			if (!"IDAT".equalsIgnoreCase(trunk.getName())) {
				continue;
			}
			inflater.setInput(trunk.getData());
			offset += inflater.inflate(outputBuffer, offset, outputBuffer.length - offset);
		}
		inflater.end();
		
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
		
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(outputBuffer);
		
		int nMaxDeflateBuffer = nMaxInflateBuffer + 1024;
		byte[] deBuffer = new byte[nMaxDeflateBuffer];
		deflater.deflate(deBuffer, 0, deBuffer.length, Deflater.FULL_FLUSH);
		deflater.finish();
		
		PNGTrunk resultDataTrunk = firstDataTrunk.clone();
		
		CRC32 crc32 = new CRC32();
		crc32.update(resultDataTrunk.getName().getBytes());
		crc32.update(deBuffer, 0, deflater.getTotalOut());
		long lCRCValue = crc32.getValue();
		
		resultDataTrunk.m_nData = deBuffer;
		resultDataTrunk.m_nCRC[0] = (byte) ((lCRCValue & 0xFF000000) >> 24);
		resultDataTrunk.m_nCRC[1] = (byte) ((lCRCValue & 0xFF0000) >> 16);
		resultDataTrunk.m_nCRC[2] = (byte) ((lCRCValue & 0xFF00) >> 8);
		resultDataTrunk.m_nCRC[3] = (byte) (lCRCValue & 0xFF);
		resultDataTrunk.m_nSize = deflater.getTotalOut();
		
		return resultDataTrunk;
	}
}
