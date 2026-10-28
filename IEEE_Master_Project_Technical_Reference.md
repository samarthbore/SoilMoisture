# IEEE Master Technical Reference: The Near-Infrared (NIR) Soil Moisture Edge-Computing & Geospatial Analytics System

**Date:** April 11, 2026  
**Document Type:** Full Project Specification & Algorithmic Whitepaper  
**Target:** IEEE Research Publication / Technical Thesis  

---

## 1. Executive Summary & Objective
The "Soil Moisture" project is a distributed IoT system designed to quantify and map soil hydration levels with high academic precision using standard consumer smartphone hardware and NoSQL cloud backends. The system overcomes the traditional limitations of RGB-based moisture detection (such as lighting noise and shadow interference) through a multi-spectral logic core (V2) and a novel "Base64-NoSQL" transport architecture. The end product provides a real-time, geospatial heatmap of a field’s moisture distribution, accessible via a corporate-minimalist analytics dashboard.

---

## 2. System Architecture: The Distributed Flow

The system is bifurcated into two primary nodes:
1.  **The Edge Intelligence Node (Android Mobile App)**: Performs local, high-frequency image processing, feature extraction, and GPS telemetry.
2.  **The Cloud Analytics Node (Web Interface)**: Aggregates global telemetry, performs trend analysis, and renders geospatial distribution maps using zero-latency webhooks.

### 2.1 The "Free-Tier" Transport Invention
One of the most critical technical innovations in this project is the **NoSQL-Binary Pipeline**. Standard cloud solutions require expensive S3 or Firebase Storage buckets for images. This project bypasses those costs by:
-   Decimating and scaling diagnostic bitmaps at the edge.
-   Transcoding the binary byte-arrays into **Base64** text strings.
-   Injecting these strings directly into Firestore JSON documents.
This architecture allows for unlimited multimedia logging within the Google Cloud "Free Tier," essentially "faking" a professional storage bucket using only a text database.

---

## 3. The Illumination Correction Engine (Mathematical Calibration)

Traditional cameras adjust "Auto-Exposure" and "White Balance" dynamically, which destroys scientific data. Even with locked camera settings (Expert Mode), environmental light shifts significantly.

### 3.1 Histogram CDF Matching
The project implements a **Reference Sheet Calibration**. The user captures a white reference sheet in a laboratory setting (Baseline) and another in the current field environment. The system executes a **Cumulative Distribution Function (CDF)** matching algorithm:
1.  **Probability Mass Function (PMF)**: $P(r_k) = \frac{n_k}{MN}$ where $n_k$ is the number of pixels with intensity $r_k$.
2.  **Cumulative Distribution**: $S_k = \sum_{j=0}^{k} P(r_j)$.
3.  **The Mapping (LUT)**: A Lookup Table (LUT) is generated that forces the "Field" CDF to align with the "Baseline" CDF. This ensures that every soil image is normalized to the same virtual light environment before any moisture is calculated.

### 3.2 Color-Preserving Scaling
To prevent "gray-out," the system does not just change brightness. It calculates the **Luminance Ratio** for every pixel and applies it to the $R, G, B$ channels proportionately:
$$ \text{Scale} = \frac{\text{CorrectedLuminance}}{\text{OriginalLuminance}} $$
$$ [R, G, B]_{\text{out}} = [R, G, B]_{\text{in}} \cdot \text{Scale} $$

---

## 4. The Scientific Accuracy Core (V2 Processing Engine)

The core extraction logic has been upgraded to a **Tri-Signal Fused Model**. Instead of just looking at "darkness," the system looks for three distinct physical signatures of wet soil.

### 4.1 Signal 1: The VARI Index (Spectral Stability)
The **Visible Atmospherically Resistant Index (VARI)** is a ratio-based spectral index that is resilient to atmospheric haze and varying color temperatures of light (e.g., golden hour vs. noon sun).
$$ VARI = \frac{Green - Red}{Green + Red - Blue + \epsilon} $$
*   **Physical Motivation**: Wetting soil changes its reflectance spectrum. VARI captures this "spectral shift" while ignoring the total volume of light.

### 4.2 Signal 2: HSV-Value (True Physical Darkening)
Color-to-grayscale conversion often loses information. We convert the $RGB$ data into the **HSV (Hue-Saturation-Value)** space.
-   **V (Value)**: Represents the pure physical intensity of light (value $0$ to $1$).
-   **Darkness Signal**: $D = 1 - V$.
*   **Physical Motivation**: Water molecules fill the air pockets between soil grains, reducing light scatter and increasing absorption (making it look darker).

### 4.3 Signal 3: Morphological Texture Roughness (Shadow Rejection)
This is the system’s most advanced feature. Standard luminance detectors often mistake shadows for water. We distinguish them using **Local Standard Deviation ($\sigma$)**.
For every pixel, the system analyzes a $3 \times 3$ local neighborhood:
1.  **Mean ($\mu$)**: $\frac{1}{9} \sum_{i=1}^{9} P_i$
2.  **Variance ($\sigma^2$)**: $\frac{1}{9} \sum_{i=1}^{9} (P_i - \mu)^2$
3.  **Roughness Factor ($R$):** $\frac{\sqrt{\sigma^2}}{K}$ (where $K$ is a normalization constant).

*   **Logic**: 
    - **Shadows** are transitionary light blocks; they are mathematically "smooth" (Low $R$).
    - **Dry Soil** is physically craggy, with crumbs, cracks, and grit; it is mathematically "rough" (High $R$).
The system applies a **Roughness Penalty**—if a dark spot is perfectly smooth, it is tagged as a shadow outlier and the moisture score is suppressed.

---

## 5. The Non-Linear Regression Pipeline

Once the three signals (Darkness, VARI, and Smoothness) are fused, the result is a "Moisture Probability" $P$. We do not map this linearly. Instead, we use a **Sigmoid Activation Function** to model the non-linear relationship of water absorption.
$$ f(x) = \frac{1}{1 + e^{-\alpha(x - 0.5)}} $$
Where $\alpha$ (Steepness) is set to $5.0$. This ensures the system is hyper-sensitive to changes in the "Moderate" moisture range (where farmers make the most decisions) while flattening the response near "Bone Dry" or "Flooded" conditions.

---

## 6. Edge Processing & Noise Decimation

The Android app executes several convolutional stages locally on the device to ensure clean data:
1.  **Separable Gaussian Blur**: Reduces high-frequency sensor noise. Instead of an $O(N^2)$ matrix, we use two 1D passes ($H$ then $V$) to conserve mobile battery.
2.  **Median Filtering**: A Rank-Selection filter that sorts the $3 \times 3$ local window and picks the middle value. This effectively deletes "Salt and Pepper" noise pixels (dead sensor pixels) without blurring the edges of the soil texture.
3.  **Trimmed-Mean Grid Aggregator**: For the $10 \times 10$ Grid Mode, the system collects thousands of moisture values per cell, **sorts them**, and discards the top and bottom 10%. This removes anomalies like a shiny pebble or a pitch-black insect that might be in the frame.

---

## 7. Geospatial Telemetry & Mapping Logic

Each data sync captures coordinates via the **FusedLocationProviderClient**.

### 7.1 NoSQL Geospatial Indexing
Coordinates are stored as `latitude` and `longitude` floats. The Firestore database triggers an immediate push to all connected web dashboards.

### 7.2 Web Dashboard Map Rendering
The dashboard uses **Leaflet.js** for the map layer. To maintain the Vercel-style corporate aesthetic, the map is not a standard colorful satellite view. It is processed through a **CSS Filter Pipeline**:
-   `grayscale(100%)`: Removes visual clutter.
-   `invert(100%)`: Aligns with the "Deep Space" dark theme.
-   `hue-rotate(180deg)`: Restores the relative contrast of terrain features.
Markers are added as **L.circleMarker** items, color-coded by the moisture percentage pulled from the JSON payload.

---

## 8. Frontend Visualization (UMD Architecture)

The web dashboard is built using a **Zero-Build/UMD (Universal Module Definition)** philosophy. This allows the index.html to be opened locally as a file or hosted on a CDN (Netlify/Vercel) without any server-side logic (Node.js/npm).
-   **Real-time Handshakes**: Uses Firestore's `onSnapshot()` WebSocket listener.
-   **Hardware Accelerated Charts**: Uses **Chart.js** via canvas rendering to show historical trends of Moisture, Average Temperature, and Calibration Strength.
-   **Responsive UX**: Implements localized CSS media queries to move from a 3-column desktop view to a single-stack mobile view seamlessly.

---

## 9. Conclusion: The Analytical Value

By combining **Spectral Indexing (VARI)**, **Color-Decoupling (HSV)**, **Morphological Texture (Roughness)**, and **Cloud Geospatial Mapping**, this project provides an end-to-end solution for modern precision agriculture. It replaces expensive specialized NIR hardware with common smartphone cameras and complex mathematical filters, making high-end soil diagnostics accessible and scalable.
