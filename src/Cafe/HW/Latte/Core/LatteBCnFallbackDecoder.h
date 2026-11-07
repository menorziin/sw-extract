#pragma once

#include "Cafe/HW/Latte/Core/LatteTextureLoader.h"
#include "Cafe/HW/Latte/Core/LatteBCnDecode.h"

// Uploads a BCn texture as an uncompressed one (RGBA8, R8 or RG8) when the driver has no BCn support.
template<BCnDecode::Kind K>
class TextureDecoder_BCnFallback : public TextureDecoder, public SingletonClass<TextureDecoder_BCnFallback<K>>
{
	static constexpr int BPT = BCnDecode::BytesPerTexel(K);

public:
	sint32 getBytesPerTexel(LatteTextureLoaderCtx* textureLoader) override
	{
		return BPT;
	}

	void decode(LatteTextureLoaderCtx* textureLoader, uint8* outputData) override
	{
		const sint32 width = textureLoader->width;
		const sint32 height = textureLoader->height;
		const sint32 rowBytes = width * BPT;
		uint8 texels[16 * 4];
		for (sint32 y = 0; y < height; y += textureLoader->stepY)
		{
			const sint32 rows = std::min(4, height - y);
			for (sint32 x = 0; x < width; x += textureLoader->stepX)
			{
				const uint8* block = LatteTextureLoader_GetInput(textureLoader, x, y);
				BCnDecode::DecodeBlock<K>(block, texels);
				const sint32 columns = std::min(4, width - x);
				uint8* dst = outputData + (size_t)y * rowBytes + (size_t)x * BPT;
				for (sint32 py = 0; py < rows; py++)
					memcpy(dst + (size_t)py * rowBytes, texels + py * 4 * BPT, (size_t)columns * BPT);
			}
		}
	}

	void decodePixelToRGBA(uint8* blockData, uint8* outputPixel, uint8 blockOffsetX, uint8 blockOffsetY) override
	{
		uint8 texels[16 * 4];
		BCnDecode::DecodeBlock<K>(blockData, texels);
		const uint8* t = texels + (blockOffsetX + blockOffsetY * 4) * BPT;
		outputPixel[0] = t[0];
		outputPixel[1] = BPT >= 2 ? t[1] : 0;
		outputPixel[2] = BPT == 4 ? t[2] : 0;
		outputPixel[3] = BPT == 4 ? t[3] : 255;
	}
};
