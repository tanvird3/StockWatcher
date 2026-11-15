# MaplePulse for Posit Connect Cloud (Shiny for Python)

This directory (`connect-cloud/`) is ready for **Git-backed deployment on Posit Connect Cloud** (`https://connect.posit.cloud`) with **no local `manifest.json` generation required**.

## Deploy to Posit Connect Cloud (GitHub Flow)

1. Push this repository to GitHub (via AI Studio's GitHub export or `git push`).
2. Sign in to [Posit Connect Cloud](https://connect.posit.cloud).
3. Click **Publish** → **Shiny** (Python).
4. Select your GitHub repository and branch, then choose **`connect-cloud/app.py`** as the primary application file.
5. Posit Connect Cloud will automatically install all packages from `connect-cloud/requirements.txt` and publish the live app with automatic re-deploys on future Git pushes.

## Run Locally (Optional)

```bash
pip install -r connect-cloud/requirements.txt
shiny run --reload connect-cloud/app.py
```
