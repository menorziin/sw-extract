#pragma once

// Integer BC1-BC5 block decoders for renderers whose driver cannot sample BCn textures
// (for example the stock Qualcomm Vulkan driver on Android). They write 8-bit texels directly,
// with the rounding of the D3D10 decode rules, instead of decoding to float and truncating.

namespace BCnDecode
{
	inline uint8 Expand5(uint32 v) { return (uint8)((v << 3) | (v >> 2)); }
	inline uint8 Expand6(uint32 v) { return (uint8)((v << 2) | (v >> 4)); }

	inline uint16 ReadU16(const uint8* p) { return (uint16)(p[0] | (p[1] << 8)); }
	inline uint32 ReadU32(const uint8* p) { return (uint32)p[0] | ((uint32)p[1] << 8) | ((uint32)p[2] << 16) | ((uint32)p[3] << 24); }

	// BC1 color block (8 bytes) -> 16 RGBA8 texels. allowTransparent: BC1 alone; BC2/BC3 always use 4-color mode.
	inline void ColorBlock(const uint8* block, uint8* rgba, bool allowTransparent)
	{
		const uint16 c0 = ReadU16(block + 0);
		const uint16 c1 = ReadU16(block + 2);
		uint8 pal[4][4];
		pal[0][0] = Expand5(c0 >> 11); pal[0][1] = Expand6((c0 >> 5) & 0x3F); pal[0][2] = Expand5(c0 & 0x1F); pal[0][3] = 255;
		pal[1][0] = Expand5(c1 >> 11); pal[1][1] = Expand6((c1 >> 5) & 0x3F); pal[1][2] = Expand5(c1 & 0x1F); pal[1][3] = 255;
		if (c0 > c1 || !allowTransparent)
		{
			for (int c = 0; c < 3; c++)
			{
				pal[2][c] = (uint8)((2 * pal[0][c] + pal[1][c] + 1) / 3);
				pal[3][c] = (uint8)((pal[0][c] + 2 * pal[1][c] + 1) / 3);
			}
			pal[2][3] = 255;
			pal[3][3] = 255;
		}
		else
		{
			for (int c = 0; c < 3; c++)
			{
				pal[2][c] = (uint8)((pal[0][c] + pal[1][c] + 1) / 2);
				pal[3][c] = 0;
			}
			pal[2][3] = 255;
			pal[3][3] = 0;
		}
		uint32 indices = ReadU32(block + 4);
		for (int i = 0; i < 16; i++, indices >>= 2)
		{
			const uint8* c = pal[indices & 3];
			rgba[i * 4 + 0] = c[0];
			rgba[i * 4 + 1] = c[1];
			rgba[i * 4 + 2] = c[2];
			rgba[i * 4 + 3] = c[3];
		}
	}

	inline uint64 Read48(const uint8* p)
	{
		uint64 v = 0;
		for (int i = 5; i >= 0; i--)
			v = (v << 8) | p[i];
		return v;
	}

	// BC3 alpha / BC4 / BC5 channel block (8 bytes), unsigned -> 16 values, written with the given stride.
	inline void UnormChannelBlock(const uint8* block, uint8* out, int stride)
	{
		const int a0 = block[0];
		const int a1 = block[1];
		uint8 pal[8];
		pal[0] = (uint8)a0;
		pal[1] = (uint8)a1;
		if (a0 > a1)
		{
			for (int i = 1; i <= 6; i++)
				pal[i + 1] = (uint8)(((7 - i) * a0 + i * a1 + 3) / 7);
		}
		else
		{
			for (int i = 1; i <= 4; i++)
				pal[i + 1] = (uint8)(((5 - i) * a0 + i * a1 + 2) / 5);
			pal[6] = 0;
			pal[7] = 255;
		}
		uint64 bits = Read48(block + 2);
		for (int i = 0; i < 16; i++, bits >>= 3)
			out[i * stride] = pal[bits & 7];
	}

	inline sint8 DivRound(int num, int den)
	{
		return (sint8)(num >= 0 ? (num + den / 2) / den : -((-num + den / 2) / den));
	}

	// BC4 / BC5 signed channel block (8 bytes) -> 16 SNORM8 values. -128 behaves as -127 (-1.0).
	inline void SnormChannelBlock(const uint8* block, uint8* out, int stride)
	{
		const int raw0 = (sint8)block[0];
		const int raw1 = (sint8)block[1];
		const int a0 = raw0 < -127 ? -127 : raw0;
		const int a1 = raw1 < -127 ? -127 : raw1;
		sint8 pal[8];
		pal[0] = (sint8)a0;
		pal[1] = (sint8)a1;
		if (raw0 > raw1) // the mode follows the stored values
		{
			for (int i = 1; i <= 6; i++)
				pal[i + 1] = DivRound((7 - i) * a0 + i * a1, 7);
		}
		else
		{
			for (int i = 1; i <= 4; i++)
				pal[i + 1] = DivRound((5 - i) * a0 + i * a1, 5);
			pal[6] = -127;
			pal[7] = 127;
		}
		uint64 bits = Read48(block + 2);
		for (int i = 0; i < 16; i++, bits >>= 3)
			out[i * stride] = (uint8)pal[bits & 7];
	}

	enum class Kind
	{
		BC1,      // -> RGBA8
		BC2,      // -> RGBA8
		BC3,      // -> RGBA8
		BC4_UNORM, // -> R8
		BC4_SNORM, // -> R8 (SNORM)
		BC5_UNORM, // -> RG8
		BC5_SNORM, // -> RG8 (SNORM)
	};

	constexpr int BytesPerTexel(Kind k)
	{
		return k == Kind::BC4_UNORM || k == Kind::BC4_SNORM ? 1 : (k == Kind::BC5_UNORM || k == Kind::BC5_SNORM ? 2 : 4);
	}

	// Decodes one 4x4 block into 16 texels laid out row by row (16 * BytesPerTexel bytes).
	template<Kind K>
	inline void DecodeBlock(const uint8* block, uint8* out)
	{
		if constexpr (K == Kind::BC1)
		{
			ColorBlock(block, out, true);
		}
		else if constexpr (K == Kind::BC2)
		{
			ColorBlock(block + 8, out, false);
			for (int i = 0; i < 16; i++)
			{
				uint8 nibble = (block[i / 2] >> ((i & 1) * 4)) & 0xF;
				out[i * 4 + 3] = (uint8)(nibble * 17);
			}
		}
		else if constexpr (K == Kind::BC3)
		{
			ColorBlock(block + 8, out, false);
			UnormChannelBlock(block, out + 3, 4);
		}
		else if constexpr (K == Kind::BC4_UNORM)
		{
			UnormChannelBlock(block, out, 1);
		}
		else if constexpr (K == Kind::BC4_SNORM)
		{
			SnormChannelBlock(block, out, 1);
		}
		else if constexpr (K == Kind::BC5_UNORM)
		{
			UnormChannelBlock(block, out, 2);
			UnormChannelBlock(block + 8, out + 1, 2);
		}
		else
		{
			SnormChannelBlock(block, out, 2);
			SnormChannelBlock(block + 8, out + 1, 2);
		}
	}
} // namespace BCnDecode
