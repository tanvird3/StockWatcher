# ==============================================================================
# MaplePulse — Canadian TSX / TSXV Equity Intelligence (Shiny for Python)
# Ready for Git-backed deployment on Posit Connect Cloud (connect.posit.cloud)
# ==============================================================================

from __future__ import annotations

import math
from datetime import datetime, timedelta
from typing import Any
from urllib.parse import quote_plus

import numpy as np
import pandas as pd
import plotly.graph_objects as go
from plotly.subplots import make_subplots
import requests
from shiny import App, reactive, render, ui
from shinywidgets import output_widget, render_widget
from statsmodels.tsa.holtwinters import Holt

# ------------------------------------------------------------------------------
# Canadian Equity Universe
# ------------------------------------------------------------------------------

TSX_UNIVERSE = pd.DataFrame(
    [
        ("RY.TO", "Royal Bank of Canada", "TSX", "Financials"),
        ("TD.TO", "Toronto-Dominion Bank", "TSX", "Financials"),
        ("SHOP.TO", "Shopify Inc.", "TSX", "Technology"),
        ("ENB.TO", "Enbridge Inc.", "TSX", "Energy"),
        ("CNQ.TO", "Canadian Natural Resources", "TSX", "Energy"),
        ("CSU.TO", "Constellation Software", "TSX", "Technology"),
        ("BN.TO", "Brookfield Corp.", "TSX", "Financials"),
        ("CP.TO", "Canadian Pacific Kansas City", "TSX", "Industrials"),
        ("CNR.TO", "Canadian National Railway", "TSX", "Industrials"),
        ("ATD.TO", "Alimentation Couche-Tard", "TSX", "Consumer"),
        ("BMO.TO", "Bank of Montreal", "TSX", "Financials"),
        ("BNS.TO", "Bank of Nova Scotia", "TSX", "Financials"),
        ("MFC.TO", "Manulife Financial", "TSX", "Financials"),
        ("ABX.TO", "Barrick Gold Corp.", "TSX", "Materials & Gold"),
        ("AEM.TO", "Agnico Eagle Mines", "TSX", "Materials & Gold"),
        ("NTR.TO", "Nutrien Ltd.", "TSX", "Materials & Gold"),
        ("BCE.TO", "BCE Inc.", "TSX", "Telecom & Utilities"),
        ("FTS.TO", "Fortis Inc.", "TSX", "Telecom & Utilities"),
        ("DOL.TO", "Dollarama Inc.", "TSX", "Consumer"),
        ("SU.TO", "Suncor Energy Inc.", "TSX", "Energy"),
        ("XIU.TO", "iShares S&P/TSX 60 ETF", "TSX", "ETFs"),
        ("XEQT.TO", "iShares Core Equity ETF", "TSX", "ETFs"),
        ("HIVE.V", "HIVE Digital Technologies", "TSXV", "Technology"),
        ("NGD.TO", "New Gold Inc.", "TSX", "Materials & Gold"),
    ],
    columns=["symbol", "name", "exchange", "sector"],
)

SYMBOL_CHOICES = {
    row["symbol"]: f"{row['symbol']} — {row['name']}"
    for _, row in TSX_UNIVERSE.iterrows()
}

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

_SESSION = requests.Session()
_SESSION.headers.update(
    {
        "User-Agent": USER_AGENT,
        "Accept": "application/json,text/plain,*/*",
        "Accept-Language": "en-CA,en;q=0.9",
    }
)
_CACHED_CRUMB: str | None = None


def _get_yahoo_crumb(force: bool = False) -> str | None:
    global _CACHED_CRUMB
    if not force and _CACHED_CRUMB:
        return _CACHED_CRUMB
    try:
        _SESSION.get("https://fc.yahoo.com", timeout=8)
    except Exception:
        pass
    try:
        resp = _SESSION.get("https://query1.finance.yahoo.com/v1/test/getcrumb", timeout=8)
        if resp.status_code == 200:
            txt = resp.text.strip()
            if txt and not txt.startswith("{"):
                _CACHED_CRUMB = txt
    except Exception:
        pass
    return _CACHED_CRUMB


def fetch_chart_quote(symbol: str, chart_range: str = "1y", interval: str = "1d") -> dict[str, Any] | None:
    encoded = quote_plus(symbol.strip())
    urls = [
        f"https://query1.finance.yahoo.com/v8/finance/chart/{encoded}?interval={interval}&range={chart_range}&includePrePost=false",
        f"https://query2.finance.yahoo.com/v8/finance/chart/{encoded}?interval={interval}&range={chart_range}&includePrePost=false",
    ]
    for url in urls:
        try:
            resp = _SESSION.get(url, timeout=10)
            if resp.status_code != 200:
                continue
            payload = resp.json()
            result_list = payload.get("chart", {}).get("result") or []
            if not result_list:
                continue
            res = result_list[0]
            meta = res.get("meta", {})
            ts = res.get("timestamp") or []
            q = (res.get("indicators", {}).get("quote") or [{}])[0]

            df = pd.DataFrame(
                {
                    "timestamp": ts,
                    "open": q.get("open") or [np.nan] * len(ts),
                    "high": q.get("high") or [np.nan] * len(ts),
                    "low": q.get("low") or [np.nan] * len(ts),
                    "close": q.get("close") or [np.nan] * len(ts),
                    "volume": q.get("volume") or [0] * len(ts),
                }
            )
            df = df.dropna(subset=["close"]).copy()
            if len(df) < 2:
                continue
            df["date"] = pd.to_datetime(df["timestamp"], unit="s", utc=True).dt.tz_convert("America/Toronto")
            df["open"] = df["open"].fillna(df["close"])
            df["high"] = df["high"].fillna(df[["open", "close"]].max(axis=1))
            df["low"] = df["low"].fillna(df[["open", "close"]].min(axis=1))
            df["volume"] = df["volume"].fillna(0).astype(float)

            price = float(meta.get("regularMarketPrice") or df["close"].iloc[-1])
            prev_close = float(
                meta.get("chartPreviousClose")
                or meta.get("previousClose")
                or (df["close"].iloc[-2] if len(df) >= 2 else price)
            )
            change = price - prev_close
            change_pct = (change / prev_close) * 100.0 if prev_close > 0 else 0.0

            sym = meta.get("symbol") or symbol
            exch = "TSXV" if sym.upper().endswith(".V") else ("TSX Index" if sym == "^GSPTSE" else "TSX")
            return {
                "symbol": sym,
                "name": meta.get("longName") or meta.get("shortName") or sym,
                "exchange": exch,
                "currency": meta.get("currency") or "CAD",
                "price": price,
                "prev_close": prev_close,
                "change": change,
                "change_pct": change_pct,
                "high_52w": float(meta.get("fiftyTwoWeekHigh") or df["high"].max()),
                "low_52w": float(meta.get("fiftyTwoWeekLow") or df["low"].min()),
                "candles": df.reset_index(drop=True),
            }
        except Exception:
            continue
    return None


def fetch_fundamentals(symbol: str) -> dict[str, Any] | None:
    encoded = quote_plus(symbol.strip())
    modules = "summaryDetail,defaultKeyStatistics,financialData,assetProfile,price"
    for attempt in range(2):
        crumb = _get_yahoo_crumb(force=(attempt == 1))
        crumb_q = f"&crumb={quote_plus(crumb)}" if crumb else ""
        url = f"https://query1.finance.yahoo.com/v10/finance/quoteSummary/{encoded}?modules={modules}{crumb_q}"
        try:
            resp = _SESSION.get(url, timeout=10)
            if resp.status_code != 200:
                continue
            results = resp.json().get("quoteSummary", {}).get("result") or []
            if not results:
                return None
            r = results[0]
            sd = r.get("summaryDetail") or {}
            ks = r.get("defaultKeyStatistics") or {}
            fd = r.get("financialData") or {}
            ap = r.get("assetProfile") or {}
            pr = r.get("price") or {}

            def raw_val(obj: dict[str, Any], key: str) -> float | None:
                val = (obj.get(key) or {}).get("raw")
                return float(val) if val is not None else None

            return {
                "sector": ap.get("sector"),
                "industry": ap.get("industry"),
                "summary": ap.get("longBusinessSummary"),
                "market_cap": raw_val(pr, "marketCap") or raw_val(sd, "marketCap"),
                "trailing_pe": raw_val(sd, "trailingPE"),
                "forward_pe": raw_val(sd, "forwardPE"),
                "price_to_book": raw_val(ks, "priceToBook"),
                "peg_ratio": raw_val(ks, "pegRatio"),
                "dividend_yield": raw_val(sd, "dividendYield"),
                "payout_ratio": raw_val(sd, "payoutRatio"),
                "beta": raw_val(sd, "beta"),
                "roe": raw_val(fd, "returnOnEquity"),
                "operating_margin": raw_val(fd, "operatingMargins"),
                "profit_margin": raw_val(fd, "profitMargins"),
                "debt_to_equity": raw_val(fd, "debtToEquity"),
                "current_ratio": raw_val(fd, "currentRatio"),
                "free_cash_flow": raw_val(fd, "freeCashflow"),
                "revenue_growth": raw_val(fd, "revenueGrowth"),
                "target_mean": raw_val(fd, "targetMeanPrice"),
            }
        except Exception:
            continue
    return None


def fetch_boc_macro() -> dict[str, float | None]:
    try:
        resp = requests.get(
            "https://www.bankofcanada.ca/valet/observations/FXCADUSD,V39079/json?recent=3",
            timeout=8,
        )
        if resp.status_code != 200:
            return {"cad_usd": None, "boc_rate": None}
        obs = resp.json().get("observations") or []
        cad_usd = None
        boc_rate = None
        for row in reversed(obs):
            if cad_usd is None and row.get("FXCADUSD", {}).get("v"):
                cad_usd = float(row["FXCADUSD"]["v"])
            if boc_rate is None and row.get("V39079", {}).get("v"):
                boc_rate = float(row["V39079"]["v"])
        return {"cad_usd": cad_usd, "boc_rate": boc_rate}
    except Exception:
        return {"cad_usd": None, "boc_rate": None}


def search_canadian_tickers(query: str) -> list[tuple[str, str]]:
    try:
        url = (
            f"https://query1.finance.yahoo.com/v1/finance/search?q={quote_plus(query.strip())}"
            "&quotesCount=15&newsCount=4&region=CA&lang=en-CA"
        )
        resp = _SESSION.get(url, timeout=8)
        if resp.status_code != 200:
            return []
        out = []
        for q in resp.json().get("quotes") or []:
            sym = q.get("symbol", "")
            exch = q.get("exchange", "")
            if sym.upper().endswith((".TO", ".V", ".CN", ".NE")) or sym == "^GSPTSE" or exch in ("TOR", "VAN"):
                nm = q.get("shortname") or q.get("longname") or sym
                out.append((sym, nm))
        return out
    except Exception:
        return []


# ------------------------------------------------------------------------------
# Quantitative Analysis Engines
# ------------------------------------------------------------------------------


def compute_technicals(quote_obj: dict[str, Any]) -> dict[str, Any]:
    df = quote_obj["candles"].copy()
    closes = df["close"]

    df["sma20"] = closes.rolling(20).mean()
    df["sma50"] = closes.rolling(50).mean()
    df["sma200"] = closes.rolling(200).mean()

    std20 = closes.rolling(20).std(ddof=0)
    df["bb_up"] = df["sma20"] + 2.0 * std20
    df["bb_dn"] = df["sma20"] - 2.0 * std20

    # Wilder RSI(14)
    delta = closes.diff()
    gain = delta.clip(lower=0.0)
    loss = -delta.clip(upper=0.0)
    avg_gain = gain.ewm(alpha=1.0 / 14.0, min_periods=14, adjust=False).mean()
    avg_loss = loss.ewm(alpha=1.0 / 14.0, min_periods=14, adjust=False).mean()
    rs = avg_gain / avg_loss.replace(0.0, np.nan)
    df["rsi14"] = 100.0 - (100.0 / (1.0 + rs))
    df.loc[avg_loss == 0.0, "rsi14"] = 100.0

    # MACD (12, 26, 9)
    ema12 = closes.ewm(span=12, adjust=False).mean()
    ema26 = closes.ewm(span=26, adjust=False).mean()
    df["macd"] = ema12 - ema26
    df["macd_signal"] = df["macd"].ewm(span=9, adjust=False).mean()
    df["macd_hist"] = df["macd"] - df["macd_signal"]

    # ATR(14) & Annualized Volatility
    prev_c = closes.shift(1)
    tr = pd.concat(
        [
            df["high"] - df["low"],
            (df["high"] - prev_c).abs(),
            (df["low"] - prev_c).abs(),
        ],
        axis=1,
    ).max(axis=1)
    atr14 = tr.ewm(alpha=1.0 / 14.0, min_periods=14, adjust=False).mean()
    log_ret = np.log(closes / closes.shift(1)).dropna()
    ann_vol = float(log_ret.tail(20).std() * math.sqrt(252.0) * 100.0) if len(log_ret) >= 20 else np.nan

    price = quote_obj["price"]
    l_sma20 = df["sma20"].iloc[-1]
    l_sma50 = df["sma50"].iloc[-1]
    l_sma200 = df["sma200"].iloc[-1]
    l_rsi = df["rsi14"].iloc[-1]
    l_macd = df["macd"].iloc[-1]
    l_sig = df["macd_signal"].iloc[-1]
    l_hist = df["macd_hist"].iloc[-1]
    l_atr = atr14.iloc[-1]

    biases = []
    if pd.notna(l_sma20):
        biases.append(1 if price > l_sma20 else -1)
    if pd.notna(l_sma50):
        biases.append(1 if price > l_sma50 else -1)
    if pd.notna(l_sma50) and pd.notna(l_sma200):
        biases.append(1 if l_sma50 > l_sma200 else -1)
    if pd.notna(l_rsi):
        biases.append(1 if l_rsi < 35 else (-1 if l_rsi > 70 else 0))
    if pd.notna(l_macd) and pd.notna(l_sig):
        biases.append(1 if l_macd > l_sig else -1)

    score = int(round(np.mean(biases) * 100)) if biases else 0
    verdict = (
        "Strong Buy"
        if score >= 55
        else "Buy"
        if score >= 20
        else "Strong Sell"
        if score <= -55
        else "Sell"
        if score <= -20
        else "Neutral"
    )

    prev = df.iloc[max(0, len(df) - 2)]
    pivot = (prev["high"] + prev["low"] + prev["close"]) / 3.0
    r1 = 2.0 * pivot - prev["low"]
    s1 = 2.0 * pivot - prev["high"]

    return {
        "series": df,
        "score": score,
        "verdict": verdict,
        "latest_rsi": float(l_rsi) if pd.notna(l_rsi) else None,
        "latest_macd_hist": float(l_hist) if pd.notna(l_hist) else None,
        "latest_atr": float(l_atr) if pd.notna(l_atr) else None,
        "ann_vol": float(ann_vol) if pd.notna(ann_vol) else None,
        "pivot": float(pivot),
        "s1": float(s1),
        "r1": float(r1),
    }


def compute_fundamental_score(quote_obj: dict[str, Any], fund: dict[str, Any] | None) -> dict[str, Any]:
    if fund is None:
        return {"score": 50, "grade": "N/A", "upside_pct": None}

    val_s = 55
    pe = fund.get("trailing_pe")
    if pe is not None:
        val_s += 22 if 5 <= pe <= 16 else (-18 if (pe > 40 or pe < 0) else 6)
    pb = fund.get("price_to_book")
    if pb is not None:
        val_s += 12 if pb <= 2.2 else (-10 if pb > 6 else 3)
    val_s = min(100, max(10, val_s))

    prof_s = 50
    roe = fund.get("roe")
    if roe is not None:
        prof_s += 25 if roe >= 0.18 else (12 if roe >= 0.10 else (-20 if roe < 0 else 0))
    op = fund.get("operating_margin")
    if op is not None:
        prof_s += 15 if op >= 0.20 else (-15 if op < 0 else 5)
    prof_s = min(100, max(10, prof_s))

    health_s = 55
    cr = fund.get("current_ratio")
    if cr is not None:
        health_s += 18 if cr >= 1.4 else (-15 if cr < 0.9 else 6)
    fcf = fund.get("free_cash_flow")
    if fcf is not None:
        health_s += 12 if fcf > 0 else -15
    health_s = min(100, max(10, health_s))

    growth_s = 50
    rg = fund.get("revenue_growth")
    if rg is not None:
        growth_s += 20 if rg >= 0.12 else (-15 if rg < -0.05 else 6)
    dy = fund.get("dividend_yield")
    if dy is not None:
        growth_s += 18 if dy >= 0.04 else (10 if dy >= 0.02 else 0)
    growth_s = min(100, max(10, growth_s))

    overall = int(round(0.28 * val_s + 0.28 * prof_s + 0.22 * health_s + 0.22 * growth_s))
    grade = (
        "A+"
        if overall >= 85
        else "A"
        if overall >= 75
        else "B+"
        if overall >= 65
        else "B"
        if overall >= 55
        else "C"
        if overall >= 45
        else "D"
    )
    t_mean = fund.get("target_mean")
    upside = ((t_mean - quote_obj["price"]) / quote_obj["price"]) * 100.0 if (t_mean and quote_obj["price"] > 0) else None
    return {"score": overall, "grade": grade, "upside_pct": upside}


def compute_forecast(quote_obj: dict[str, Any], horizon_days: int = 30) -> dict[str, Any] | None:
    df = quote_obj["candles"]
    closes = df["close"].tail(120).to_numpy(dtype=float)
    n = len(closes)
    if n < 15:
        return None

    log_p = np.log(closes)
    holt_fit = Holt(log_p, damped_trend=True, initialization_method="estimated").fit(optimized=True)
    holt_mean = np.asarray(holt_fit.forecast(horizon_days), dtype=float)

    x = np.arange(n, dtype=float)
    slope, intercept = np.polyfit(x, log_p, 1)
    y_hat = slope * x + intercept
    ss_tot = float(np.sum((log_p - np.mean(log_p)) ** 2))
    ss_res = float(np.sum((log_p - y_hat) ** 2))
    r2 = max(0.0, min(1.0, 1.0 - ss_res / ss_tot)) if ss_tot > 0 else 0.0
    ols_proj = log_p[-1] + slope * np.arange(1, horizon_days + 1, dtype=float)

    w_ols = min(0.75, max(0.35, 0.35 + 0.40 * r2))
    blended_log = (1.0 - w_ols) * holt_mean + w_ols * ols_proj

    daily_sigma = min(0.08, max(0.004, float(np.std(np.diff(log_p), ddof=1))))
    steps = np.arange(1, horizon_days + 1, dtype=float)
    sigma_t = daily_sigma * np.sqrt(steps)

    base_p = np.exp(blended_log)
    last_date = df["date"].iloc[-1]
    future_dates = [last_date + timedelta(days=int(s)) for s in steps]

    mape = None
    dir_acc = None
    if n >= 35:
        errs = []
        hits = []
        for idx in range(max(20, n - 20), n - 5):
            sub = Holt(log_p[:idx], damped_trend=True, initialization_method="estimated").fit(optimized=True)
            pred = float(np.exp(sub.forecast(5)[-1]))
            act = closes[idx + 4]
            errs.append(abs(act - pred) / act * 100.0)
            hits.append((pred >= closes[idx - 1]) == (act >= closes[idx - 1]))
        mape = float(np.mean(errs))
        dir_acc = float(np.mean(hits) * 100.0)

    return {
        "history": df.tail(45)[["date", "close"]],
        "future": pd.DataFrame(
            {
                "date": future_dates,
                "base": base_p,
                "upper1": base_p * np.exp(sigma_t),
                "lower1": base_p * np.exp(-sigma_t),
                "upper2": base_p * np.exp(1.96 * sigma_t),
                "lower2": base_p * np.exp(-1.96 * sigma_t),
            }
        ),
        "projected_price": float(base_p[-1]),
        "projected_pct": float(((base_p[-1] - quote_obj["price"]) / quote_obj["price"]) * 100.0),
        "bull_95": float((base_p * np.exp(1.96 * sigma_t))[-1]),
        "bear_95": float((base_p * np.exp(-1.96 * sigma_t))[-1]),
        "r2": r2,
        "daily_vol_pct": daily_sigma * 100.0,
        "mape": mape,
        "dir_acc": dir_acc,
    }


# ------------------------------------------------------------------------------
# Shiny UI (Midnight Canadian Financial Terminal)
# ------------------------------------------------------------------------------

TERMINAL_CSS = """
body { background-color: #0B1622; color: #F1F5F9; font-family: 'Plus Jakarta Sans', system-ui, sans-serif; }
.navbar { background-color: #102030 !important; border-bottom: 1px solid #233B54; }
.card { background-color: #102030 !important; border: 1px solid #233B54 !important; border-radius: 14px !important; color: #F1F5F9; }
.bslib-sidebar-layout > .sidebar { background-color: #102030 !important; border-right: 1px solid #233B54 !important; color: #F1F5F9; }
.metric-box { background: #162A3E; border: 1px solid #233B54; border-radius: 12px; padding: 12px 14px; }
.mono-num { font-family: 'JetBrains Mono', monospace; font-variant-numeric: tabular-nums; }
.bull-txt { color: #10B981; font-weight: 600; }
.bear-txt { color: #F43F5E; font-weight: 600; }
.warn-txt { color: #F59E0B; font-weight: 600; }
.cyan-txt { color: #38BDF8; font-weight: 600; }
.form-control, .form-select { background-color: #0B1622 !important; color: #F1F5F9 !important; border-color: #233B54 !important; }
"""

app_ui = ui.page_navbar(
    ui.nav_panel(
        "Markets & Analyzer",
        ui.layout_sidebar(
            ui.sidebar(
                ui.input_select("selected_symbol", "Active Canadian Equity", choices=SYMBOL_CHOICES, selected="RY.TO"),
                ui.input_text("custom_search", "Search TSX / TSXV Ticker", placeholder="e.g. L.TO, HIVE.V, BCE.TO"),
                ui.input_action_button("search_btn", "Search Exchange", class_="btn-outline-info btn-sm w-100 mb-2"),
                ui.output_ui("search_status_ui"),
                ui.hr(),
                ui.input_select(
                    "chart_range",
                    "Chart Lookback",
                    choices={"5d": "5 Days", "1mo": "1 Month", "3mo": "3 Months", "6mo": "6 Months", "1y": "1 Year", "5y": "5 Years"},
                    selected="1y",
                ),
                ui.input_checkbox("show_candles", "Candlestick Chart", value=True),
                ui.input_checkbox("show_sma", "SMA 20 / SMA 50 Overlays", value=True),
                ui.input_checkbox("show_bb", "Bollinger Bands (20, 2σ)", value=False),
                ui.hr(),
                ui.input_action_button("toggle_watchlist_btn", "Add / Remove Watchlist", class_="btn-success w-100 mb-2"),
                ui.input_action_button("refresh_symbol_btn", "Refresh Live Quote", class_="btn-outline-light btn-sm w-100"),
                width=300,
            ),
            ui.output_ui("macro_banner_ui"),
            ui.output_ui("stock_header_ui"),
            ui.navset_card_tab(
                ui.nav_panel(
                    "Technical Analysis",
                    ui.br(),
                    output_widget("tech_chart", height="490px"),
                    ui.br(),
                    ui.output_ui("tech_metrics_ui"),
                ),
                ui.nav_panel(
                    "Fundamental Analysis",
                    ui.br(),
                    ui.output_ui("fundamental_ui"),
                ),
                ui.nav_panel(
                    "Quantitative Forecast",
                    ui.br(),
                    ui.input_radio_buttons(
                        "forecast_horizon",
                        "Forecast Horizon",
                        choices={"7": "7 Trading Days", "30": "30 Trading Days", "90": "90 Trading Days"},
                        selected="30",
                        inline=True,
                    ),
                    output_widget("forecast_chart", height="390px"),
                    ui.br(),
                    ui.output_ui("forecast_metrics_ui"),
                ),
            ),
        ),
    ),
    ui.nav_panel(
        "TSX Screener",
        ui.card(
            ui.card_header("Multi-Factor TSX / TSXV Technical Scanner"),
            ui.layout_columns(
                ui.input_select("screener_sector", "Sector", choices=["All"] + sorted(TSX_UNIVERSE["sector"].unique().tolist())),
                ui.input_select(
                    "screener_filter",
                    "Signal Filter",
                    choices=["All", "Bullish (Buy / Strong Buy)", "Oversold (RSI <= 35)", "Overbought (RSI >= 70)"],
                ),
                ui.div(ui.br(), ui.input_action_button("run_screener_btn", "Scan Live TSX Universe", class_="btn-success w-100")),
                col_widths=[4, 4, 4],
            ),
            ui.br(),
            ui.output_data_frame("screener_table"),
        ),
    ),
    ui.nav_panel(
        "Watchlist",
        ui.card(
            ui.card_header("Tracked Canadian Equities"),
            ui.output_data_frame("watchlist_table"),
            ui.br(),
            ui.input_action_button("refresh_watchlist_btn", "Refresh Watchlist Quotes", class_="btn-outline-info btn-sm"),
        ),
    ),
    ui.nav_panel(
        "Real-Time Alerts",
        ui.layout_columns(
            ui.card(
                ui.card_header("Arm Price or Technical Alert"),
                ui.input_select("alert_symbol", "Ticker", choices=TSX_UNIVERSE["symbol"].tolist(), selected="RY.TO"),
                ui.input_select(
                    "alert_condition",
                    "Condition",
                    choices={
                        "ABOVE": "Price Crosses Above (CAD)",
                        "BELOW": "Price Drops Below (CAD)",
                        "PCT_GAIN": "Intraday Gain >= (%)",
                        "PCT_DROP": "Intraday Drop <= (%)",
                        "RSI_ABOVE": "RSI(14) Overbought >=",
                        "RSI_BELOW": "RSI(14) Oversold <=",
                    },
                ),
                ui.input_numeric("alert_target", "Threshold Value", value=160.0, min=0.01, step=0.5),
                ui.input_action_button("create_alert_btn", "Arm Real-Time Alert", class_="btn-warning w-100 mb-3"),
                ui.input_checkbox("live_polling_on", "Enable 20s Real-Time Polling", value=True),
                ui.input_action_button("scan_alerts_now_btn", "Scan Active Alerts Now", class_="btn-outline-info w-100"),
            ),
            ui.card(
                ui.card_header("Active Rules & Triggered History"),
                ui.h6("Armed Rules"),
                ui.output_data_frame("alerts_table"),
                ui.hr(),
                ui.h6("Triggered Alert Log"),
                ui.output_data_frame("alert_events_table"),
            ),
            col_widths=[5, 7],
        ),
    ),
    title=ui.span(
        ui.strong("MaplePulse", style="color:#F1F5F9;"),
        ui.span(" TSX/TSXV", class_="badge bg-success ms-2", style="font-size:0.7rem;"),
    ),
    header=ui.head_content(
        ui.tags.link(
            rel="stylesheet",
            href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600&family=Plus+Jakarta+Sans:wght@400;600;700&family=Space+Grotesk:wght@600;700&display=swap",
        ),
        ui.tags.style(TERMINAL_CSS),
    ),
)


# ------------------------------------------------------------------------------
# Server Logic
# ------------------------------------------------------------------------------


def server(input, output, session):
    watchlist_rv = reactive.value(TSX_UNIVERSE.head(3).copy())
    alerts_rv = reactive.value(
        pd.DataFrame(columns=["ID", "Symbol", "Condition", "Target", "Active"])
    )
    events_rv = reactive.value(
        pd.DataFrame(columns=["Time", "Symbol", "Message"])
    )
    screener_rv = reactive.value(pd.DataFrame())
    search_msg_rv = reactive.value("")

    macro_rv = reactive.value(fetch_boc_macro())
    tsx_index_rv = reactive.value(fetch_chart_quote("^GSPTSE", "3mo", "1d"))

    @reactive.calc
    def active_data():
        _ = input.refresh_symbol_btn()
        sym = input.selected_symbol()
        rng = input.chart_range()
        interval = "15m" if rng == "5d" else ("1wk" if rng == "5y" else "1d")

        quote_obj = fetch_chart_quote(sym, rng, interval)
        if quote_obj is None:
            return None
        tech = compute_technicals(quote_obj)
        fund = fetch_fundamentals(sym)
        fund_eval = compute_fundamental_score(quote_obj, fund)
        return {"quote": quote_obj, "tech": tech, "fund": fund, "fund_eval": fund_eval}

    @reactive.effect
    @reactive.event(input.search_btn)
    def _handle_search():
        q = input.custom_search().strip()
        if not q:
            return
        hits = search_canadian_tickers(q)
        if hits:
            merged = dict(SYMBOL_CHOICES)
            for sym, nm in hits:
                merged[sym] = f"{sym} — {nm}"
            ui.update_select("selected_symbol", choices=merged, selected=hits[0][0])
            ui.update_select("alert_symbol", choices=list(merged.keys()), selected=hits[0][0])
            search_msg_rv.set(f"Found {len(hits)} Canadian matches; loaded {hits[0][0]}.")
        else:
            search_msg_rv.set("No TSX / TSXV tickers matched.")

    @render.ui
    def search_status_ui():
        msg = search_msg_rv()
        return ui.div(msg, class_="small text-info mb-2") if msg else None

    @render.ui
    def macro_banner_ui():
        tsx = tsx_index_rv()
        m = macro_rv()
        tsx_span = (
            ui.span(
                f"{tsx['price']:,.2f} ({tsx['change_pct']:+.2f}%)",
                class_="bull-txt mono-num ms-2" if tsx["change"] >= 0 else "bear-txt mono-num ms-2",
            )
            if tsx
            else ui.span("Unavailable", class_="text-secondary ms-2")
        )
        boc_txt = f"BoC Rate: {m['boc_rate']:.2f}%  |  " if m.get("boc_rate") is not None else ""
        fx_txt = f"CAD/USD: {m['cad_usd']:.4f}" if m.get("cad_usd") is not None else ""
        return ui.div(
            ui.div(ui.span("S&P/TSX COMPOSITE ", class_="warn-txt small"), tsx_span),
            ui.div(ui.span(boc_txt), ui.span(fx_txt, class_="cyan-txt"), class_="mono-num small"),
            class_="metric-box mb-3 d-flex flex-wrap justify-content-between align-items-center",
        )

    @render.ui
    def stock_header_ui():
        dat = active_data()
        if dat is None:
            return ui.div("Unable to load live TSX quote. Retry.", class_="alert alert-danger")
        q = dat["quote"]
        t = dat["tech"]
        cls = "bull-txt" if q["change"] >= 0 else "bear-txt"
        return ui.div(
            ui.div(
                ui.h3(f"{q['symbol']} ({q['exchange']})", class_="mb-0"),
                ui.div(q["name"], class_="text-secondary small"),
            ),
            ui.div(
                ui.h3(f"{q['currency']} ${q['price']:.2f}", class_="mono-num mb-0"),
                ui.div(
                    f"{q['change']:+.2f} ({q['change_pct']:+.2f}%)  •  Technical: {t['verdict']} ({t['score']})",
                    class_=f"{cls} mono-num small",
                ),
                class_="text-end",
            ),
            class_="metric-box mb-3 d-flex flex-wrap justify-content-between align-items-center",
        )

    @render_widget
    def tech_chart():
        dat = active_data()
        if dat is None:
            return go.Figure()
        df = dat["tech"]["series"]
        fig = make_subplots(
            rows=3,
            cols=1,
            shared_xaxes=True,
            vertical_spacing=0.04,
            row_heights=[0.58, 0.21, 0.21],
        )
        if input.show_candles():
            fig.add_trace(
                go.Candlestick(
                    x=df["date"],
                    open=df["open"],
                    high=df["high"],
                    low=df["low"],
                    close=df["close"],
                    name=dat["quote"]["symbol"],
                    increasing_line_color="#10B981",
                    decreasing_line_color="#F43F5E",
                ),
                row=1,
                col=1,
            )
        else:
            fig.add_trace(
                go.Scatter(x=df["date"], y=df["close"], mode="lines", name="Close", line=dict(color="#10B981", width=2)),
                row=1,
                col=1,
            )
        if input.show_sma():
            fig.add_trace(go.Scatter(x=df["date"], y=df["sma20"], name="SMA 20", line=dict(color="#F59E0B", width=1.4)), row=1, col=1)
            fig.add_trace(go.Scatter(x=df["date"], y=df["sma50"], name="SMA 50", line=dict(color="#A78BFA", width=1.4)), row=1, col=1)
        if input.show_bb():
            fig.add_trace(go.Scatter(x=df["date"], y=df["bb_up"], name="BB Upper", line=dict(color="#38BDF8", dash="dot", width=1)), row=1, col=1)
            fig.add_trace(
                go.Scatter(
                    x=df["date"],
                    y=df["bb_dn"],
                    name="BB Lower",
                    line=dict(color="#38BDF8", dash="dot", width=1),
                    fill="tonexty",
                    fillcolor="rgba(56,189,248,0.08)",
                ),
                row=1,
                col=1,
            )

        fig.add_trace(go.Scatter(x=df["date"], y=df["rsi14"], name="RSI(14)", line=dict(color="#F59E0B", width=1.6)), row=2, col=1)
        fig.add_hline(y=70, line_dash="dot", line_color="#F43F5E", row=2, col=1)
        fig.add_hline(y=30, line_dash="dot", line_color="#10B981", row=2, col=1)

        hist_colors = ["#10B981" if (v or 0) >= 0 else "#F43F5E" for v in df["macd_hist"].fillna(0)]
        fig.add_trace(go.Bar(x=df["date"], y=df["macd_hist"], name="MACD Hist", marker_color=hist_colors), row=3, col=1)
        fig.add_trace(go.Scatter(x=df["date"], y=df["macd"], name="MACD", line=dict(color="#38BDF8", width=1.3)), row=3, col=1)
        fig.add_trace(go.Scatter(x=df["date"], y=df["macd_signal"], name="Signal", line=dict(color="#F59E0B", width=1.2)), row=3, col=1)

        fig.update_layout(
            paper_bgcolor="#102030",
            plot_bgcolor="#0B1622",
            font=dict(color="#F1F5F9"),
            xaxis_rangeslider_visible=False,
            margin=dict(l=40, r=20, t=30, b=20),
            legend=dict(orientation="h", y=1.06),
        )
        return fig

    @render.ui
    def tech_metrics_ui():
        dat = active_data()
        if dat is None:
            return None
        t = dat["tech"]
        rsi_txt = f"{t['latest_rsi']:.1f}" if t["latest_rsi"] is not None else "--"
        atr_txt = f"${t['latest_atr']:.2f}" if t["latest_atr"] is not None else "--"
        vol_txt = f"{t['ann_vol']:.1f}%" if t["ann_vol"] is not None else "--"
        return ui.layout_columns(
            ui.div(ui.div("RSI (14)", class_="small text-secondary"), ui.h5(rsi_txt, class_="mono-num"), class_="metric-box"),
            ui.div(ui.div("Pivot / S1 / R1", class_="small text-secondary"), ui.h5(f"${t['pivot']:.2f} / ${t['s1']:.2f} / ${t['r1']:.2f}", class_="mono-num"), class_="metric-box"),
            ui.div(ui.div("ATR (14)", class_="small text-secondary"), ui.h5(atr_txt, class_="mono-num"), class_="metric-box"),
            ui.div(ui.div("Ann. Volatility", class_="small text-secondary"), ui.h5(vol_txt, class_="mono-num"), class_="metric-box"),
            col_widths=[3, 3, 3, 3],
        )

    @render.ui
    def fundamental_ui():
        dat = active_data()
        if dat is None:
            return None
        f = dat["fund"]
        ev = dat["fund_eval"]
        if f is None:
            return ui.div("Detailed corporate fundamentals unavailable for this ticker (e.g., index or ETF).", class_="alert alert-secondary")

        def fmt(v: float | None, mult: float = 1.0, suffix: str = "") -> str:
            return "--" if v is None else f"{v * mult:.2f}{suffix}"

        upside_node = (
            ui.span(
                f"Analyst Target Upside: {ev['upside_pct']:+.1f}%",
                class_="bull-txt mono-num" if ev["upside_pct"] >= 0 else "bear-txt mono-num",
            )
            if ev["upside_pct"] is not None
            else None
        )
        return ui.TagList(
            ui.div(
                ui.div(
                    ui.h5(f"Fundamental Health Grade: {ev['grade']} ({ev['score']}/100)"),
                    ui.div(f"Sector: {f.get('sector') or '--'}  •  Industry: {f.get('industry') or '--'}", class_="small text-secondary"),
                ),
                upside_node,
                class_="metric-box mb-3 d-flex justify-content-between align-items-center",
            ),
            ui.layout_columns(
                ui.div(ui.div("Trailing P/E", class_="small text-secondary"), ui.h5(fmt(f.get("trailing_pe")), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("Forward P/E", class_="small text-secondary"), ui.h5(fmt(f.get("forward_pe")), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("Dividend Yield", class_="small text-secondary"), ui.h5(fmt(f.get("dividend_yield"), 100.0, "%"), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("ROE", class_="small text-secondary"), ui.h5(fmt(f.get("roe"), 100.0, "%"), class_="mono-num"), class_="metric-box"),
                col_widths=[3, 3, 3, 3],
            ),
            ui.br(),
            ui.layout_columns(
                ui.div(ui.div("Price / Book", class_="small text-secondary"), ui.h5(fmt(f.get("price_to_book")), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("Profit Margin", class_="small text-secondary"), ui.h5(fmt(f.get("profit_margin"), 100.0, "%"), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("Debt / Equity", class_="small text-secondary"), ui.h5(fmt(f.get("debt_to_equity"), 1.0, "%"), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("Analyst Target", class_="small text-secondary"), ui.h5(fmt(f.get("target_mean")), class_="mono-num"), class_="metric-box"),
                col_widths=[3, 3, 3, 3],
            ),
            ui.br(),
            ui.div(f["summary"], class_="metric-box small text-secondary") if f.get("summary") else None,
        )

    @reactive.calc
    def forecast_res():
        dat = active_data()
        if dat is None:
            return None
        return compute_forecast(dat["quote"], int(input.forecast_horizon()))

    @render_widget
    def forecast_chart():
        fc = forecast_res()
        if fc is None:
            return go.Figure()
        hist = fc["history"]
        fut = fc["future"]
        fig = go.Figure()
        fig.add_trace(go.Scatter(x=hist["date"], y=hist["close"], name="Historical Close", line=dict(color="#10B981", width=2.2)))
        fig.add_trace(go.Scatter(x=fut["date"], y=fut["upper2"], showlegend=False, line=dict(width=0)))
        fig.add_trace(
            go.Scatter(
                x=fut["date"],
                y=fut["lower2"],
                name="95% Confidence Cone",
                fill="tonexty",
                fillcolor="rgba(56,189,248,0.14)",
                line=dict(width=0),
            )
        )
        fig.add_trace(go.Scatter(x=fut["date"], y=fut["upper1"], showlegend=False, line=dict(width=0)))
        fig.add_trace(
            go.Scatter(
                x=fut["date"],
                y=fut["lower1"],
                name="68% Confidence Cone",
                fill="tonexty",
                fillcolor="rgba(56,189,248,0.26)",
                line=dict(width=0),
            )
        )
        fig.add_trace(go.Scatter(x=fut["date"], y=fut["base"], name="Ensemble Base Projection", line=dict(color="#38BDF8", dash="dash", width=2.4)))
        fig.update_layout(
            paper_bgcolor="#102030",
            plot_bgcolor="#0B1622",
            font=dict(color="#F1F5F9"),
            yaxis_title="Price (CAD)",
            margin=dict(l=40, r=20, t=30, b=20),
            legend=dict(orientation="h", y=1.06),
        )
        return fig

    @render.ui
    def forecast_metrics_ui():
        fc = forecast_res()
        if fc is None:
            return None
        mape_txt = f"{fc['mape']:.1f}% / {fc['dir_acc']:.0f}%" if fc["mape"] is not None else "--"
        return ui.layout_columns(
            ui.div(ui.div("Bear Target (95%)", class_="small text-secondary"), ui.h5(f"${fc['bear_95']:.2f}", class_="bear-txt mono-num"), class_="metric-box"),
            ui.div(ui.div("Base Projection", class_="small text-secondary"), ui.h5(f"${fc['projected_price']:.2f} ({fc['projected_pct']:+.2f}%)", class_="cyan-txt mono-num"), class_="metric-box"),
            ui.div(ui.div("Bull Target (95%)", class_="small text-secondary"), ui.h5(f"${fc['bull_95']:.2f}", class_="bull-txt mono-num"), class_="metric-box"),
            ui.div(ui.div("Backtest MAPE / Acc", class_="small text-secondary"), ui.h5(mape_txt, class_="mono-num"), class_="metric-box"),
            col_widths=[3, 3, 3, 3],
        )

    @reactive.effect
    @reactive.event(input.run_screener_btn)
    def _run_screener():
        df_u = TSX_UNIVERSE.copy()
        if input.screener_sector() != "All":
            df_u = df_u[df_u["sector"] == input.screener_sector()]
        rows = []
        for _, r in df_u.iterrows():
            q = fetch_chart_quote(r["symbol"], "3mo", "1d")
            if q is None:
                continue
            t = compute_technicals(q)
            rows.append(
                {
                    "Symbol": r["symbol"],
                    "Name": r["name"],
                    "Sector": r["sector"],
                    "Price_CAD": round(q["price"], 2),
                    "Change_%": round(q["change_pct"], 2),
                    "RSI_14": round(t["latest_rsi"], 1) if t["latest_rsi"] is not None else None,
                    "Score": t["score"],
                    "Signal": t["verdict"],
                    "Ann_Vol_%": round(t["ann_vol"], 1) if t["ann_vol"] is not None else None,
                }
            )
        out = pd.DataFrame(rows)
        if not out.empty:
            flt = input.screener_filter()
            if flt == "Bullish (Buy / Strong Buy)":
                out = out[out["Signal"].isin(["Buy", "Strong Buy"])]
            elif flt == "Oversold (RSI <= 35)":
                out = out[out["RSI_14"].notna() & (out["RSI_14"] <= 35)]
            elif flt == "Overbought (RSI >= 70)":
                out = out[out["RSI_14"].notna() & (out["RSI_14"] >= 70)]
            out = out.sort_values("Score", ascending=False)
        screener_rv.set(out)

    @render.data_frame
    def screener_table():
        return render.DataGrid(screener_rv())

    @reactive.effect
    @reactive.event(input.toggle_watchlist_btn)
    def _toggle_wl():
        sym = input.selected_symbol()
        wl = watchlist_rv().copy()
        if sym in wl["symbol"].values:
            watchlist_rv.set(wl[wl["symbol"] != sym])
            ui.notification_show(f"Removed {sym} from watchlist", type="message")
        else:
            dat = active_data()
            nm = dat["quote"]["name"] if dat else sym
            ex = dat["quote"]["exchange"] if dat else "TSX"
            new_row = pd.DataFrame([{"symbol": sym, "name": nm, "exchange": ex, "sector": "TSX"}])
            watchlist_rv.set(pd.concat([wl, new_row], ignore_index=True))
            ui.notification_show(f"Added {sym} to watchlist", type="message")

    @render.data_frame
    def watchlist_table():
        _ = input.refresh_watchlist_btn()
        wl = watchlist_rv()
        rows = []
        for _, r in wl.iterrows():
            q = fetch_chart_quote(r["symbol"], "3mo", "1d")
            if q is None:
                rows.append({"Symbol": r["symbol"], "Name": r["name"], "Price_CAD": None, "Change_%": None, "RSI_14": None, "Verdict": "--"})
            else:
                t = compute_technicals(q)
                rows.append(
                    {
                        "Symbol": q["symbol"],
                        "Name": q["name"],
                        "Price_CAD": round(q["price"], 2),
                        "Change_%": round(q["change_pct"], 2),
                        "RSI_14": round(t["latest_rsi"], 1) if t["latest_rsi"] is not None else None,
                        "Verdict": t["verdict"],
                    }
                )
        return render.DataGrid(pd.DataFrame(rows))

    @reactive.effect
    @reactive.event(input.create_alert_btn)
    def _create_alert():
        tgt = input.alert_target()
        if tgt is None or tgt <= 0:
            return
        df = alerts_rv().copy()
        aid = f"ALT-{datetime.now().strftime('%H%M%S')}-{len(df) + 1}"
        new_row = pd.DataFrame(
            [{"ID": aid, "Symbol": input.alert_symbol(), "Condition": input.alert_condition(), "Target": float(tgt), "Active": True}]
        )
        alerts_rv.set(pd.concat([df, new_row], ignore_index=True))
        ui.notification_show(f"Armed alert for {input.alert_symbol()}", type="message")

    def _evaluate_alerts():
        al = alerts_rv().copy()
        if al.empty or not al["Active"].any():
            return
        ev = events_rv().copy()
        changed = False
        for idx in al.index[al["Active"]]:
            sym = al.at[idx, "Symbol"]
            q = fetch_chart_quote(sym, "3mo", "1d")
            if q is None:
                continue
            t = compute_technicals(q)
            cond = al.at[idx, "Condition"]
            tgt = float(al.at[idx, "Target"])
            rsi = t["latest_rsi"]
            hit = (
                (cond == "ABOVE" and q["price"] >= tgt)
                or (cond == "BELOW" and q["price"] <= tgt)
                or (cond == "PCT_GAIN" and q["change_pct"] >= tgt)
                or (cond == "PCT_DROP" and q["change_pct"] <= -abs(tgt))
                or (cond == "RSI_ABOVE" and rsi is not None and rsi >= tgt)
                or (cond == "RSI_BELOW" and rsi is not None and rsi <= tgt)
            )
            if hit:
                changed = True
                al.at[idx, "Active"] = False
                msg = f"{sym} triggered {cond} {tgt:.2f} (Live CAD {q['price']:.2f})"
                ev = pd.concat(
                    [pd.DataFrame([{"Time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "Symbol": sym, "Message": msg}]), ev],
                    ignore_index=True,
                )
                ui.notification_show(msg, type="warning", duration=10)
        if changed:
            alerts_rv.set(al)
            events_rv.set(ev)

    @reactive.effect
    @reactive.event(input.scan_alerts_now_btn)
    def _scan_now():
        _evaluate_alerts()

    @reactive.effect
    def _poll_loop():
        reactive.invalidate_later(20)
        if input.live_polling_on():
            with reactive.isolate():
                _evaluate_alerts()

    @render.data_frame
    def alerts_table():
        return render.DataGrid(alerts_rv())

    @render.data_frame
    def alert_events_table():
        return render.DataGrid(events_rv())


app = App(app_ui, server)
