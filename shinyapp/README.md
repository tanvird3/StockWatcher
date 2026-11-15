# MaplePulse for shinyapps.io (R Shiny)

This directory contains a standalone **R Shiny** port of **MaplePulse** configured for direct deployment to [shinyapps.io](https://www.shinyapps.io/).

## Included Capabilities
- **Live Canadian TSX / TSXV Feed**: Fetches real-time and historical OHLCV data (`^GSPTSE`, `.TO`, `.V`) plus Bank of Canada Valet API macro rates (`FXCADUSD` and BoC Overnight Rate `V39079`).
- **Technical Analysis (`TTR` + `plotly`)**: Interactive Candlestick / Line charts with SMA(20), SMA(50), Bollinger Bands (20, 2σ), RSI(14), MACD(12,26,9), ATR(14), Pivot levels, and a multi-factor TSX Screener.
- **Fundamental Analysis**: Evaluates Trailing/Forward P/E, P/B, Dividend Yield, ROE, Profit Margin, Debt/Equity, Free Cash Flow, and Analyst Consensus Upside.
- **Quantitative Forecasting (`forecast`)**: Damped Holt's Exponential Smoothing + Log-Linear OLS drift ensemble with 68% (1σ) and 95% (2σ) confidence cones and rolling out-of-sample backtest MAPE / directional accuracy.
- **Real-Time Alerts (`reactiveTimer`)**: Polls active TSX price, percentage move, and RSI(14) thresholds every 20 seconds and logs triggered events.

## Deploying to shinyapps.io

1. Install the required CRAN packages locally once in R:
   ```r
   install.packages(c(
     "shiny", "bslib", "httr", "jsonlite",
     "TTR", "plotly", "forecast", "DT", "rsconnect"
   ))
   ```

2. Authorize your shinyapps.io account in RStudio or the R console:
   ```r
   rsconnect::setAccountInfo(
     name   = "<YOUR_SHINYAPPS_ACCOUNT>",
     token  = "<YOUR_TOKEN>",
     secret = "<YOUR_SECRET>"
   )
   ```

3. Deploy the `shinyapp` directory:
   ```r
   rsconnect::deployApp(
     appDir  = "shinyapp",
     appName = "maplepulse-tsx"
   )
   ```

## Deploying R Shiny to Posit Connect Cloud

If deploying this R version to **Posit Connect Cloud** from GitHub, generate `manifest.json` inside `shinyapp/` first:
```r
rsconnect::writeManifest(appDir = "shinyapp")
```
Commit `shinyapp/manifest.json` and point Posit Connect Cloud to `shinyapp/app.R`. (Alternatively, use `connect-cloud/app.py` + `connect-cloud/requirements.txt` for zero-config GitHub publishing via Shiny for Python.)
