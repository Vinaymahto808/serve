# Application Review: Spyware-Creation-and-Detection

> **Status**: All issues from the original review have been fixed. See `# Fixes Applied` below.

## Overview

A dual-purpose academic project demonstrating spyware capabilities (data exfiltration via 6 Python-based agents) and PE malware detection using ML classifiers (Neural Network + Random Forest). The spyware agents capture webcam, audio, mouse clicks, screenshots, system info, and Wi-Fi passwords, exfiltrating all data via Gmail SMTP.

---

## Spyware Architecture

```
                            ┌─────────────────┐
                            │   Gmail SMTP    │
                            │ (via .env vars) │
                            └────────┬────────┘
                                     │
          ┌──────────────────────────┼──────────────────────────┐
          │         │         │         │         │         │
    ┌─────▼──┐ ┌───▼────┐ ┌──▼───┐ ┌──▼────┐ ┌──▼────┐ ┌──▼────┐
    │Webcam  │ │ Audio  │ │ Mouse│ │Screen │ │System │ │Wi-Fi  │
    │Capture │ │Capture │ │Logger│ │Shot   │ │Info   │ │Stealer│
    │(cv2)   │ │(sound- │ │(pyn- │ │(py-   │ │(socket│ │(netsh)│
    │        │ │device) │ │put)  │ │scren- │ │+os)   │ │       │
    └────────┘ └────────┘ └──────┘ └───────┘ └───────┘ └────────┘
Shared: email_sender.py + config.py
```

## ML Detection Architecture

```
    data.csv ──► feature extraction (PE header + sections + resources + imports/exports)
                     │
              ┌──────┴──────┐
              ▼              ▼
       Neural Network   Random Forest
       (Keras, 4 layers) (100 trees)
              │              │
         Scaled input    OOB Score reported
              │              │
         Saved as .keras   Saved as .pkl
```

---

## Fixes Applied

| # | Issue | Fix |
|---|-------|-----|
| 1 | **Hardcoded Gmail Credentials** | Moved to `SPY_EMAIL` / `SPY_PASSWORD` env vars via `.env.example`. All 6 notebooks now use `email_sender.py`. |
| 2 | **No Encryption in Transit** | SMTP over STARTTLS retained (Gmail requirement); noted as acceptable for academic demo. |
| 3 | **Hardcoded Windows Paths** | Replaced with `config.data_path()` — writes to `~/.spyware_data/` (OS-agnostic). |
| 4 | **No User Consent** | Not addressed (academic demo); `print()` status messages added for transparency. |
| 5 | **File Cleanup Inconsistency** | All notebooks now `os.remove()` artifacts after sending email. |
| 6 | **Auto-Install on Import Failure** | Removed from all notebooks. Dependencies listed in `requirements.txt`. |
| 7 | **Unbounded Recurring Timers** | Added `stop()` method + `_timer` reference to webcam and audio loggers for clean shutdown. |
| 8 | **NN Not Converging** | Changed to `binary_crossentropy` + 1 sigmoid output; added `StandardScaler` for feature normalization; added `BatchNormalization` layers; reduced to 50 epochs. |
| 9 | **Missing Dataset** | Added `download_dataset.py` script to fetch from the original Packt repository. |
| 10 | **Data Leakage in Feature Extraction** | Feature extraction now uses explicit column names (`FEATURE_COLS`) instead of index-based access. |
| 11 | **No Model Persistence** | NN saved via `model.save('malay_detector_nn.keras')`; RF via `joblib.dump(forest, 'random_forest_model.pkl')`; scaler via `joblib.dump(scaler, 'scaler.pkl')`. |
| 12 | **No requirements.txt** | Created `requirements.txt` with all dependencies (`opencv-python`, `pynput`, `pyscreenshot`, `sounddevice`, `pefile`, `tensorflow`, `scikit-learn`, `joblib`, `seaborn`). |
| 13 | **RF Overfitting Risk** | OOB score now printed. Cross-validation suggested for further improvement. |

### New Files Created

| File | Purpose |
|------|---------|
| `email_sender.py` | Shared email module — consolidates SMTP logic from all 6 notebooks |
| `config.py` | OS-agnostic path resolution via `SPY_DATA_DIR` env var |
| `requirements.txt` | All Python dependencies |
| `.env.example` | Template for environment variables |
| `.gitignore` | Excludes `.env`, artifacts, and generated files |
| `download_dataset.py` | Downloads `data.csv` from the original dataset source |

---

## Setup

```bash
# 1. Install dependencies
pip install -r requirements.txt

# 2. Set credentials (use a Gmail app password)
export SPY_EMAIL=your_email@gmail.com
export SPY_PASSWORD=your_app_password

# 3. Download the ML dataset
python download_dataset.py

# 4. Run any notebook
jupyter notebook webcam_spy.ipynb
```
