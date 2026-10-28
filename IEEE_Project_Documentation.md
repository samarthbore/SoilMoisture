# Comprehensive Technical Architecture & Algorithmic Implementation: Near-Infrared (NIR) Computed Soil Moisture Analytics System

This document serves as the exhaustive architectural, mathematical, and algorithmic reference for the IEEE research paper, explicitly detailing how digital images are processed into quantifiable soil moisture data using edge-computing algorithms. 

---

## 1. System Architecture Overview
The system implements a distributed IoT pipeline designed for high-resolution computational imaging on low-resource hardware:
1. **The Edge Sensor Node (Android Mobile Device):** Captures high-resolution bitmap frames. Acts as the primary computational engine, executing multi-pass separable convolutions, noise decimation, and non-linear regression mapping entirely locally on the mobile CPU/GPU.
2. **The Zero-Cost Transport Layer (Firebase Firestore):** A modified NoSQL architecture that intercepts raw image buffers, aggressively transcodes them into standard strings via Base64 mapping, and bypasses traditional expensive cloud storage systems.
3. **The Localhost Visualization Engine (UMD Web Dashboard):** A zero-dependency HTML5 application using websocket streams (`onSnapshot`) to render metrics seamlessly.

---

## 2. Dynamic Illumination Normalization Algorithm
**Problem:** Field images are heavily distorted by ambient lighting variance (e.g., direct solar irradiance vs. dense cloud cover). Attempting to extract luminance-based moisture metrics from raw uncalibrated imagery leads to a massive false-positive rate.
**Implementation Solution (`IlluminationCorrector.kt`):**
The system implements a "Reference White Sheet" Look-Up Table (LUT) calibration.

1. **Baseline Extraction:** The user captures a generic white reference sheet in highly controlled laboratory lighting. 
2. **Field Extraction:** The user captures the *same* white sheet in the field environment immediately prior to soil sample testing.
3. **Array Compression:** Because high-resolution 4K images are too expensive to process entirely, both sheets are forcefully scaled down to an interpolated 500x500 square frame.
4. **Correction Ratio Calculation:** For every color channel (Red, Green, Blue), the aggregate average is quantified. The correction factor $C$ for a specific channel $c \in \{r, g, b\}$ is established as the ratio between the laboratory baseline $B_c$ and the current environmental state $E_c$:
   $$C_c = \frac{B_c \cdot 1.0}{E_{c}} $$
5. **LUT Generation & Application:** This generates an array of scalar modifiers. When an uncalibrated soil image is introduced, the algorithm systematically traverses every pixel $P_{x,y}$ and multiplies its RGB values by the localized correction factors $C_c$, coercing the output to the valid 0-255 arbitrary byte range. This normalizes the soil sample, artificially simulating what the dirt would look like if it was sitting under laboratory lighting.

---

## 3. The 8-Stage Image Data Extraction Pipeline (`SoilMoistureProcessor.kt`)

Once the soil bitmap is calibrated for illumination, the core logic extracts quantifiable moisture insights. Digital pixel values cannot directly represent soil moisture without accounting for sensor noise, outliers, and physical absorption curves. The system processes the image using an eight-stage analytical pipeline:

### Stage 1: Multi-Channel Extraction
The bitmap is iterated to extract discrete Red, Green, Blue arrays, and computes a base normalized luminance array $L$ using standard spatial weighting:
$$ L_i = \min(\max(\lfloor 0.299 \cdot R_i + 0.587 \cdot G_i + 0.114 \cdot B_i \rfloor, 0), 255) $$

### Stage 2: Separable Gaussian Blur Convolution (Noise Decimation)
To eliminate random camera sensor noise and aggressive JPEG artifacts, a Gaussian blur is applied with `BLUR_RADIUS = 2`. 
To conserve mobile CPU, the system performs a Separable Convolution. Instead of calculating a full $O(N^2)$ 5x5 kernel matrix for every pixel, it passes a 1D horizontal pass, followed immediately by a 1D vertical pass, reducing the time complexity strictly to $O(2N)$.

### Stage 3: The Advanced Scientific Accuracy Core (V2)
The system has been upgraded from simple RGB/Luminance logic to a **Fused Multi-Signal Engine**:

#### A. Visible Atmospherically Resistant Index (VARI)
Traditional darkness measures fail in variable light. We utilize VARI to normalize environmental noise:
$$ VARI = \frac{Green - Red}{Green + Red - Blue} $$
This ratio shifts significantly when moisture changes the soil's spectral reflectance, independent of absolute intensity.

#### B. HSV-Value Decoupling
Standard gray-scale ($0.299R + 0.587G + 0.114B$) can be skewed by strong primary colors. By converting to the **HSV (Hue-Saturation-Value)** space, we extract the pure **Value (V)** channel, which represents the physical photon intensity, and the **Saturation (S)**, which correlates with the deepness of moisture-rich soil colors.

#### C. Surface Texture Roughness (Physical Filter)
The primary failure mode of optical sensors is "Shadow Interference". We implemented a **3x3 Local Variance Kernel** to distinguish physical state:
- **Shadows**: Mathematically "Smooth" (Low Variance).
- **Dry/Drying Soil**: Mathematically "Rough" (High Variance due to soil grains/cracks).

The system applies a **Roughness Penalty**—if a dark region is perfectly smooth, the algorithm identifies it as a shadow outlier rather than wet soil, drastically reducing false positives.

### Stage 4: Adaptive Percentile Normalization
Because soil contains highly anomalous micro-debris (e.g., highly reflective quartz pebbles generating $L=255$ or pitch-black insect shadows generating $L=0$), standard mathematical averages will brutally skew results. 
The algorithm sorts the luminance and $CMI$ arrays. It executes a hard percentile extraction by ignoring the extrema (`PERCENTILE_LOW = 0.02`, `PERCENTILE_HIGH = 0.98`), effectively clipping the bottom and top 2% of anomalous pixels.

### Stage 5: Weighted Feature Synthesis
The algorithm aggregates the normalized luminance $L_{norm}$ (weighted at 75%) alongside the $CMI_{norm}$ (weighted at 25%, `COLOR_INDEX_WEIGHT = 0.25`). This yields the raw linear moisture score.

### Stage 6: Sigmoid Non-Linear Thermal Regression Mapping
Physical realities show that the optical absorption curve of water is not strictly linear across all concentrations. To explicitly map the digital array into accurate thermal estimates, the algorithm forces the combined pixel array $X_i$ through a customized non-linear Sigmoid curve $S(x)$ parameterized by steepness scalar `SIGMOID_K = 5.0`:
$$ S(x) = \frac{1}{1 + e^{-((x - 0.5) \cdot 5.0)}} $$
This mathematical transformation drastically increases sensor sensitivity in the critical mid-ranges (30% to 70% moisture) while aggressively flattening the extremities.

### Stage 7: Median Salt-and-Pepper Noise Filtering
The output matrix inevitably contains statistical "speckle" anomalies from structural dirt clumps. A standard average filter would blur the physical perimeter. Instead, a Median Post-Filter (`MEDIAN_RADIUS = 1`) mathematically sorts the local 3x3 surrounding kernel for every pixel and reassigns the center value to the exact median. This completely eradicates individual speckle points while maintaining razor-sharp geometric edges of actual dirt clods.

### Stage 8: Data Normalization
The array is explicitly linearly mapped into hard 0-100% moisture vectors.

---

## 4. Analytical Data Compilation Modes

Once the 0-100% array is generated, the system computes the final data points using two user-accessible branches:

### Mode A: 10x10 Grid Analysis (Macro Statistics)
The $500 \times 500$ pixel array is sliced down into $10 \times 10$ structural cells. 
*   **Trimmed Mean Integration:** Inside each cell, the algorithm collects all local pixel arrays. It strips the top and bottom 10% of outlier data from that specific cell, calculating a stable local average. This produces ultra-rugged, highly resilient macro statistics robust against uneven soil topologies.

### Mode B: Per-Pixel Thermal Synthesis (Micro Analytics)
Utilizes the entire 250,000 pixel array without down-sampling. 
*   **Heatmap Pipeline:** It evaluates every float through a localized rendering loop, mapping the 0-100 probability into a 6-stop High-Contrast RGB Thermal Array mapping: 
   `(0=Purple, 20=Red, 40=Orange, 60=Yellow, 80=Green, 100=Cyan)`. 
*   **Extraction:** The image constructs an ARGB heatmap bitmap in real-time, pulling comprehensive Min/Max arrays and passing the Bitmap straight to the Cloud Layer. 

---

## 5. Bypassing Edge-Cloud Constraints: The Base64 Payload Model

**The Problem Paradigm:**
Transmitting four diagnostic bitmapped images (Raw, Corrected, Grid Heatmap, Pixel Heatmap) alongside standard float telemetry is extraordinarily computationally expensive on standard Cloud bandwidth architectures, rapidly consuming paid-tier allowances on typical MongoDB/Firebase Storage buckets.

**The NoSQL Bypass Invention:**
1. **Decimation:** The edge app severely scales down all 4 bitmaps.
2. **Buffer Encoding:** The bitmaps are rapidly compressed directly into internal `ByteArray` chunks.
3. **Base64 String Transcoding:** The Android engine transcodes the byte matrices into single URL-safe **Base64 Strings**.
4. **JSON Payload Wrapping:** The system packages the spatial telemetry (Moisture percentage, Minimum Temperature, Maximum Temperature, Average Latitude/Longitude derived from GPS API calls) alongside the monolithic Base64 Strings.
5. **NoSQL Injection:** The entirety of the massive payload document is sent straight through Firebase Firestore. Instead of billing per Megabyte transferred, Firestore bills per *Single Document Action*. Consequently, this forces a massive multimedia transport pipeline through a lightweight text backend, optimizing edge-cloud scaling models exponentially for free-tier hardware structures.

---

## 6. The UMD Web Analytics Interface

The frontend architecture serves as an instant-accessibility analytics node, built completely devoid of typical structural package environments (No Node.js, No React hooks, No Webpack binaries). 
*   **Zero-Build Mechanics:** Leveraging Universal Module Definition (UMD) SDK headers imported directly inside `index.html`. 
*   **Aggressive Data Ingestion:** By mapping `db.collection("readings").orderBy("timestamp_ms").onSnapshot()`, the javascript DOM sustains a persistent websocket tether directly to the Google Cloud array. The instant the Android phone executes an upload, the DOM recalculates array lengths, manipulates specific document IDs, and updates innerHTML logic in real-time (with zero HTTP requests required from the user).
*   **Hardware-Accelerated Visualization:** Float arrays intercept from the websocket and push to `Chart.js` engines overlaying dynamically configured `<canvas>` items, smoothing geometry via bezier tension loops while hiding noisy grid layouts for Vercel/Blynk-style hyper-minimalism.
