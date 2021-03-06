package com.trivore.image.png;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.junit.Assert;
import org.junit.Test;

public class IOSPngConverterTests {
	
	private final File resourcesDir = new File("src/test/resources");
	
	@Test
	public void testAppIconConversion() throws Exception {
		File sourceFile = new File(resourcesDir, "AppIcon60x60@2x.png");
		
		File targetFile = File.createTempFile("AppIcon60x60@2x-target", ".png");
		targetFile.deleteOnExit();
		try (IOSPngConverter converter = new IOSPngConverter(sourceFile)) {
			converter.convert(targetFile);
		}
		
		String targetContentType = Files.probeContentType(targetFile.toPath());
		
		/* Assert that size and content type matches what is expected. */
		Assert.assertEquals("image/png", targetContentType);
		Assert.assertEquals(8431, targetFile.length());
		
		/* Assert that the image can be read using ImageIO. */
		BufferedImage img = ImageIO.read(targetFile);
		Assert.assertNotNull(img);
		
		int width = img.getWidth();
		int height = img.getHeight();
		
		Assert.assertEquals(120, width);
		Assert.assertEquals(120, height);
		
		targetFile.delete();
	}
}
