import com.emc.mongoose.config.GeneratorConfig;
import com.emc.mongoose.config.GeneratorDecoder;
import com.emc.mongoose.config.reader.ConfigReader;
import org.junit.Test;

import javax.json.JsonObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 Created on 11.07.16.
 */
public class GeneratorDecoderTest {

	@Test
	public void shouldCreateConfig() throws Exception{
		final GeneratorDecoder generatorDecoder = new GeneratorDecoder();
		final JsonObject defaults = ConfigReader.readJson("defaults.json");
		assertNotNull("The configuration file was read wrong", defaults);
		final GeneratorConfig generatorConfig =
			generatorDecoder.decode(defaults);
		assertEquals("Decoding was failed", generatorConfig.item().getType(), "data");
	}

}
