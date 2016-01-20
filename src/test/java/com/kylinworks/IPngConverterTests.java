package com.kylinworks;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.junit.Assert;
import org.junit.Test;

public class IPngConverterTests {
	
	private final File resourcesDir = new File("src/test/resources");
	
	@Test
	public void testAppIconConversion() throws Exception {
		File sourceFile = new File(resourcesDir, "AppIcon60x60@2x.png");
		String sourceContentType = Files.probeContentType(sourceFile.toPath());
		Assert.assertEquals("image/x-apple-ios-png", sourceContentType);
		
		File targetFile = File.createTempFile("AppIcon60x60@2x-target", ".png");
		targetFile.deleteOnExit();
		IPngConverter converter = new IPngConverter(sourceFile, targetFile);
		converter.convert();
		
		String targetContentType = Files.probeContentType(targetFile.toPath());
		
		/* Assert that size and content type matches what is expected. */
		Assert.assertEquals("image/png", targetContentType);
		Assert.assertEquals(8424, targetFile.length());
		
		/* Assert that the image can be read using ImageIO. */
		BufferedImage img = ImageIO.read(targetFile);
		Assert.assertNotNull(img);
	}
}
