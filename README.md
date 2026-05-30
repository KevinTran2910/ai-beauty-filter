# REAS AI Filter SDK - WebAssembly Build Guide

## Overview

This guide provides comprehensive instructions for building and deploying the REAS AI Filter SDK as a WebAssembly (WASM) module using the Emscripten toolchain. The SDK enables real-time image and video filtering capabilities in web browsers through high-performance C++ code compiled to WebAssembly.

## Prerequisites

Ensure the following dependencies are installed on your development environment:

- **Git** (version 2.0 or higher)
- **Python** (version 3.7 or higher)  
- **CMake** (version 3.16 or higher)

## Installation and Setup

### 1. Clone Emscripten SDK Repository

Open terminal and execute the following commands:

```bash
git clone https://github.com/emscripten-core/emsdk.git
cd emsdk
```

### 2. Install and Activate Emscripten

Run the following commands to set up the toolchain:

```bash
./emsdk install latest
./emsdk activate latest
source ./emsdk_env.sh  # For Linux/macOS
# emsdk_env.bat        # For Windows
```

**Note:** Environment configuration must be executed in each new terminal session.

## Build Process

### 3. Compile C++ Application to WebAssembly

Execute the build script from the project root directory:

```bash
# Grant execution permissions
chmod +x ./script/build_wasm.sh

# Run the build process
./script/build_wasm.sh
```

## Build Output

### 4. Output Directory Structure

The compilation process generates the following directory structure under `output/`:

```
output/
├── bin/                    # Executable binaries and demo files
│   ├── app.wasm           # Main WebAssembly module
│   ├── app.js             # JavaScript wrapper/glue code
│   ├── app.data           # Embedded data and resources
│   ├── index.html         # Demo HTML file
│   ├── serve.py           # Web server startup script (auto-copied)
│   └── logo/              # Logo directory (auto-copied)
│       └── NTQ-logo.png   # Company logo
├── lib/                   # Static libraries
│   ├── libbeautyfilter.a  # Beauty filter processing library
│   └── libyuv.a           # YUV video processing library
├── res/                   # Filter resources (textures, lookup tables)
│   ├── blusher.png        # Blush effect texture
│   ├── lookup_*.png       # Color filter lookup tables
│   └── ...                # Additional filter assets
└── include/               # Header files for integration
```

### 5. Core File Descriptions

| File | Purpose | 
|------|---------|
| `app.wasm` | WebAssembly module containing filter processing logic |
| `app.js` | JavaScript glue code for WASM module interaction | 
| `app.data` | Embedded data file containing necessary resources | 
| `index.html` | Demo page for testing filter functionality | 
| `serve.py` | Simple web server for serving the demo |

## Development and Testing

### 6. Running the Demo

WASM demo files can be found in the `output/bin` directory. To run the demo:

```bash
# Navigate to the output directory
cd output/bin

# Start a local web server
python3 serve.py
```

The demo will be available at `http://localhost:8000` by default.

## Production Integration

### 7. Web Application Integration

To integrate the WASM Filter into your web project, you only need **3 core files** from the `output/bin/` directory:

#### Required Files for Integration:
- `app.wasm` - Main WebAssembly module 
- `app.js` - JavaScript wrapper for WASM interaction 
- `app.data` - Embedded data and resources

#### Reference Files (Optional):
- `index.html` - Demo file for usage reference
- `serve.py` - Development web server
- `logo/` - Demo assets

#### Basic Integration Example:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>WASM Filter Integration</title>
</head>
<body>
    <script src="app.js"></script>
    <script>
        // Load WASM module
        Module().then(function(wasmModule) {
            console.log('WASM Filter loaded successfully!');
            
            // Use functions from WASM module
            // wasmModule.yourFilterFunction();
        }).catch(function(error) {
            console.error('Failed to load WASM module:', error);
        });
    </script>
</body>
</html>
```

#### Advanced Integration with Promise API:

```javascript
// Modern async/await pattern
async function initializeFilterSDK() {
    try {
        const wasmModule = await Module();
        console.log('SDK loaded successfully');
        
        // Initialize filter pipeline
        const filterInstance = new wasmModule.FilterProcessor();
        
        return filterInstance;
    } catch (error) {
        console.error('SDK initialization error:', error);
        throw error;
    }
}

// Usage
initializeFilterSDK()
    .then(filter => {
        // Use filter instance for processing
    })
    .catch(error => {
        // Handle initialization errors
    });
```

## Deployment Considerations

### 8. Important Notes for Production:

- **Automatic Loading**: The `app.data` file will be automatically loaded by `app.js` when needed
- **File Placement**: Ensure all 3 core files are placed in the same directory on your web server
- **CORS Requirements**: A web server with CORS headers support is required to load WASM (cannot run directly from `file://` protocol)
- **Development vs Production**: The `lib/`, `res/`, and `include/` directories are only needed during development, not for production deployment

### 9. Web Server Configuration

#### Minimum CORS Headers Required:
```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type
```

#### MIME Type Configuration:
```
.wasm → application/wasm
.js   → application/javascript
.data → application/octet-stream
```

## Troubleshooting

### Common Issues:

1. **CORS Errors**: Ensure files are served from an HTTP server, not opened directly as files
2. **Module Loading Failures**: Verify all three core files are in the same directory and accessible
3. **Memory Issues**: Monitor WebAssembly memory usage for large image processing operations
4. **Browser Compatibility**: Check WebAssembly support in target browsers


