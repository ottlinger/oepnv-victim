# Project artwork

Original, editable vector artwork for **oepnv-victim**, with matching PNG exports.
The social preview depicts a delayed regional train, a technical fault, a pulled
emergency brake and a traffic jam. The compact logo combines a train with a
disruption warning.

## Assets

| File | Dimensions | Intended use |
| --- | --- | --- |
| [`social-preview.png`](social-preview.png) | 1280 × 640 | GitHub social preview and link cards |
| [`social-preview.svg`](social-preview.svg) | Scalable, 2:1 | Editable source for banners and other formats |
| [`logo.png`](logo.png) | 512 × 512 | Project icon, avatar and documentation |
| [`logo.svg`](logo.svg) | Scalable, square | Editable source for future logo variants |

![Project logo](logo.png)

## Set the GitHub social preview

In the repository on GitHub, open **Settings → General → Social preview → Edit →
Upload an image** and select `social-preview.png`. Committing the file does not
set the repository's social preview automatically.

## Editing and exporting

The SVG files are the source of truth: retain them when creating further artwork.
They can be edited in Inkscape, Illustrator or a text editor. The social preview
uses DejaVu Sans and DejaVu Sans Mono with fallback fonts; its appearance may vary
slightly if those fonts are unavailable. The square logo has no font dependency.

Recreate the PNG exports with `rsvg-convert` (part of librsvg):

```sh
rsvg-convert --width 1280 --height 640 \
  --output docs/social-preview.png docs/social-preview.svg
rsvg-convert --width 512 --height 512 \
  --output docs/logo.png docs/logo.svg
```

On macOS, librsvg can be installed with `brew install librsvg`. These exports are
not part of the Maven build.

### Palette

- Navy: `#101b32` / `#07101e`
- Mint: `#61dfca`
- Coral: `#d94251`
- Amber: `#ffc064`
- Light text: `#f2f6fc`
