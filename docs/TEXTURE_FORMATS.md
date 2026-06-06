# Browser Texture Formats

MCEF exposes browser frames as Blaze3D `GpuTexture` objects using `GpuFormat.RGBA8_UNORM`.
That format describes the logical GPU texture format, not necessarily the original CEF
software paint byte order.

## Software Paint

CEF software paint provides 4-byte BGRA pixels. MCEF uploads those bytes through Blaze3D's
standard texture APIs without CPU-side channel conversion. The texture is still created as
`GpuFormat.RGBA8_UNORM`, but sampled red and blue channels are swapped relative to normal
RGBA data.

Consumers must check `MCEFRenderer.isBGRA()` and use a shader that swaps sampled red and
blue channels when it returns `true`.

The browser `Identifier` is registered with Minecraft's `TextureManager` through an
`AbstractTexture` adapter. In the software paint path this adapter exposes the renderer-owned
Blaze3D `GpuTexture`, `GpuTextureView`, and `GpuSampler` directly; it does not wrap or bind an
OpenGL texture id.

## Accelerated Paint

Accelerated paint imports platform textures and then copies them with Blaze3D
`CommandEncoder.copyTextureToTexture`. The imported texture may also need red/blue channel
swapping depending on the platform and CEF color type. `MCEFRenderer.isBGRA()` reports the
required sampling behavior for the currently exposed texture.

## Consumer Contract

`MCEFRenderer.isBGRA()` means "sampling this texture requires red/blue channel swapping".
It does not mean the exposed `GpuTexture` has a different Blaze3D `GpuFormat`.

Using a normal textured pipeline on a texture where `isBGRA()` is `true` will render red and
blue channels incorrectly.
