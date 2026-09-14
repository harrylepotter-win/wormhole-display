# Optimizing Text Sharpness on Meta Portal+ from macOS

This guide documents the root causes of blurry text when extending or mirroring a Mac display to the Meta Portal+ Gen 2 (14″, 2160×1440, ~185 PPI) over AirPlay, along with proven methods to achieve crisp typography and UI readability.

---

## 1. Executive Summary & Diagnostic Findings

Live diagnostics and pixel-level frame captures on macOS and Meta Portal+ revealed five primary factors that degrade text sharpness:

| Factor | Mechanism | Visual Impact |
|---|---|---|
| **Accidental Screen Mirroring** | macOS mirrors the MacBook Pro's built-in 3024×1964 screen, fractionally scaling it down ($0.714\times$) to fit 2160×1402. | Severe blurriness and interpolation across all 1-pixel font stems and borders. |
| **macOS 1× Font Rasterization** | Apple removed subpixel antialiasing (ClearType equivalent) in macOS Mojave. Standard 1× rendering relies solely on grayscale smoothing. | On a ~185 PPI panel at 1×, 1-pixel font strokes are surrounded by a soft, fuzzy gray halo. |
| **AirPlay Video Compression (YUV 4:2:0)** | AirPlay compresses frames in real time using H.264 (or HEVC) with 4:2:0 chroma subsampling. | Color resolution is halved (1080×720). Colored text (syntax highlighting, orange/blue links) smears; high-contrast edges show DCT ringing. |
| **No Native macOS HiDPI Mode** | macOS treats the advertised 2160×1440 AirPlay display strictly as a 1× non-Retina canvas. | No automatic 2× vector Retina glyph rendering by CoreGraphics. |
| **Physical Typography Scale** | Standard desktop 12–14px fonts on a 14″ 1440p screen are physically under 2 mm tall. | Character strokes are only 1 physical pixel wide on the panel, straining human vision and video encoders. |

---

## 2. Root Cause Analysis

### A. Fractional Downscaling in Mirror Mode
When connecting over macOS Screen Mirroring, macOS often defaults to **"Mirror Built-in Display"**:
* MacBook Pro Liquid Retina display: `3024 × 1964` (16:10.4 aspect ratio).
* Meta Portal+ panel: `2160 × 1440` (3:2 aspect ratio).
* To fit the stream, macOS resizes the entire desktop to **2160 × 1402**.
* Because $2160 / 3024 \approx 0.714$ is a non-integer scaling factor, every single glyph, window line, and icon is bilinearly/bicubically blurred before video encoding.

### B. The Death of Subpixel Antialiasing on macOS
Windows retains ClearType RGB subpixel antialiasing, which keeps 1440p (100–160 PPI) monitors relatively sharp. Modern macOS, by contrast:
* Was redesigned exclusively for 220+ PPI Retina screens (like Apple Studio Display at 218 PPI or MacBook Liquid Retina at 254 PPI).
* Replaced subpixel hinting with pure grayscale antialiasing.
* On a ~185 PPI display at 1×, thin strokes blend across pixel boundaries, making fonts look faint and hazy.

### C. AirPlay Lossy Video Codec & Chroma Subsampling
Unlike DisplayPort or HDMI cables that carry uncompressed 4:4:4 RGB data:
* AirPlay encodes desktop video over H.264 (AVC) or H.265 (HEVC) using **YUV 4:2:0 chroma subsampling**.
* Luma (brightness) is full 2160×1440, but **Chroma (color) is subsampled to 1080×720**.
* Fine colored text (e.g. orange text in dark themes, green/blue code syntax) loses half its spatial color resolution.
* High-contrast transitions (pure `#FFFFFF` text on pitch `#000000` black) trigger compression macroblock ringing and deblocking smoothing.

---

## 3. Step-by-Step Solutions & Workarounds

### Step 1: Ensure True Extended Desktop (Never Mirror)
1. On your Mac, open **System Settings → Displays** (or click **Screen Mirroring** in Control Center).
2. Click on **Wormhole Display** (or your custom receiver name).
3. Set **"Use as"** to **"Extended display"** (or select **"Use As Separate Display"**).
4. Verify the resolution is set to **2160 × 1440**.
> *Result: Eliminates the 0.714× fractional downscaling artifact completely.*

---

### Step 2: Instant Relief — Browser / App Zoom (`Cmd` + `+`)
If you keep the native 2160×1440 resolution for maximum workspace:
* In Chrome, Safari, Slack, or VS Code, press **`Cmd` + `+`** to set zoom to **110%** or **125%**.
* **Why it works:** At 100%, font strokes are 1 pixel thin, making them susceptible to video blur. At 125%, font strokes are 2–3 solid pixels wide, increasing contrast and legibility exponentially.

---

### Step 3: Switch to a Scaled 3:2 Resolution in macOS Settings
1. In macOS **System Settings → Displays**, select **Wormhole Display**.
2. Toggle **"Show all resolutions"**.
3. Choose **1620 × 1080** or **1344 × 896**.
* **Why it works:** These resolutions match the panel's native **3:2 aspect ratio** (no black bars or aspect warping), but scale all UI elements up by 33% to 60%. Font glyphs become significantly larger and survive H.264 compression cleanly.

---

### Step 4: True Retina (HiDPI) Text via BetterDisplay *(Best Overall Quality)*
To get razor-sharp vector Retina text identical to a MacBook screen:

1. Install [BetterDisplay](https://github.com/waydabber/BetterDisplay) (free version is sufficient):
   ```bash
   brew install --cask betterdisplay
   ```
2. Launch BetterDisplay from Applications.
3. In the BetterDisplay menu bar icon:
   * Select **Wormhole Display**.
   * Enable **Edit System Configuration of this Display** / **Enable HiDPI**.
   * Pick a scaled resolution such as **1440 × 960 HiDPI** or **1620 × 1080 HiDPI**.
* **Why it works:** macOS will render the desktop internally at 2× Retina resolution (`2880 × 1920` or `3240 × 2160`) with full vector font sharpness, then downsample cleanly to the receiver's 2160×1440 physical panel. You get crisp Retina typography with balanced screen real estate.

---

### Step 5: Adjust or Disable macOS Font Smoothing in Terminal
Modern macOS applies heavy grayscale blurring on non-Retina displays. You can tune this via Terminal:

```bash
# Option A: Turn off font smoothing completely (sharpest, pixel-aligned edges):
defaults -currentHost write -g AppleFontSmoothing -int 0

# Option B: Light font smoothing:
defaults -currentHost write -g AppleFontSmoothing -int 1
```

* To apply: Restart your applications (or log out and log back in).
* To revert to macOS defaults:
  ```bash
  defaults -currentHost delete -g AppleFontSmoothing
  ```

---

### Step 6: Color Theme Adjustments for 4:2:0 Codecs
Because AirPlay uses 4:2:0 chroma subsampling:
* **Avoid pure black and white extremes:** Pure `#FFFFFF` text on pitch `#000000` black causes maximum edge ringing.
* **Prefer soft dark mode:** Dark gray backgrounds (`#1E1E1E` or `#24292E`) with soft white text (`#E1E4E8`) render with significantly fewer compression artifacts.
* **Light mode:** Standard dark text on light/white backgrounds is dominated by the luma channel (which is full 2160×1440 resolution) and produces the sharpest text over AirPlay.
