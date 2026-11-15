# ==============================================================================
# MaplePulse — Canadian TSX / TSXV Equity Intelligence (Shiny for Python)
# Ready for Git-backed deployment on Posit Connect Cloud (connect.posit.cloud)
# ==============================================================================

from __future__ import annotations

import concurrent.futures
import difflib
import math
import string
import threading
import time
from datetime import datetime, timedelta, timezone
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

try:
    import yfinance as yf  # Optional TLS-impersonated fallback
except Exception:
    yf = None

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
        ("HIVE.TO", "HIVE Digital Technologies", "TSX", "Technology"),
        ("NGD.TO", "New Gold Inc.", "TSX", "Materials & Gold"),
    ],
    columns=["symbol", "name", "exchange", "sector"],
)

_SKIP_INSTRUMENT_TOKENS = (".PR.", ".PF.", ".DB", ".WT", ".RT", ".NT", ".NO")


def _fetch_tmx_prefix_bucket(ex: str, ch: str, hdrs: dict[str, str]) -> tuple[str, list[dict[str, Any]]]:
    url = f"https://www.tsx.com/json/company-directory/search/{ex}/^{ch}"
    try:
        resp = requests.get(url, headers=hdrs, timeout=8)
        if resp.status_code != 200:
            return ex, []
        return ex, (resp.json().get("results") or [])
    except Exception:
        return ex, []


def load_full_tsx_directory() -> tuple[pd.DataFrame, dict[str, str]]:
    rows: list[tuple[str, str, str, str]] = [
        (r["symbol"], r["name"], r["exchange"], r["sector"]) for _, r in TSX_UNIVERSE.iterrows()
    ]
    seen: set[str] = {r[0] for r in rows}
    choices: dict[str, str] = {
        r[0]: f"{r[0]} — {r[1]} ({r[2]})" for r in rows
    }

    hdrs = {
        "User-Agent": (
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        ),
        "Referer": "https://www.tsx.com/",
    }
    prefixes = list(string.ascii_uppercase + string.digits)
    tasks = [("tsx", ch) for ch in prefixes] + [("tsxv", ch) for ch in prefixes]

    raw_buckets: dict[str, list[dict[str, Any]]] = {"tsx": [], "tsxv": []}
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futs = [pool.submit(_fetch_tmx_prefix_bucket, ex, ch, hdrs) for ex, ch in tasks]
        for fut in concurrent.futures.as_completed(futs):
            ex, items = fut.result()
            raw_buckets[ex].extend(items)

    extra_rows: list[tuple[str, str, str, str]] = []
    for ex, exch_label, suffix in (("tsx", "TSX", ".TO"), ("tsxv", "TSXV", ".V")):
        for item in raw_buckets[ex]:
            comp_name = (item.get("name") or "").strip()
            instruments = item.get("instruments") or [
                {"symbol": item.get("symbol"), "name": comp_name}
            ]
            for inst in instruments:
                raw_sym = (inst.get("symbol") or "").strip().upper()
                if not raw_sym or any(tok in raw_sym for tok in _SKIP_INSTRUMENT_TOKENS):
                    continue
                canon = f"{raw_sym}{suffix}"
                if canon in seen:
                    continue
                seen.add(canon)
                label_name = comp_name or (inst.get("name") or raw_sym).strip()
                sector_label = "CDR (CAD Hedged)" if "CDR" in label_name.upper() else f"{exch_label} Listed"
                extra_rows.append((canon, label_name, exch_label, sector_label))

    extra_rows.sort(key=lambda r: r[0])
    for r in extra_rows:
        rows.append(r)
        choices[r[0]] = f"{r[0]} — {r[1]} ({r[2]})"

    return pd.DataFrame(rows, columns=["symbol", "name", "exchange", "sector"]), choices


DIRECTORY_DF, SYMBOL_CHOICES = load_full_tsx_directory()

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

TMX_GRAPHQL_URL = "https://app-money.tmx.com/graphql"
_TMX_HEADERS = {
    "Content-Type": "application/json",
    "User-Agent": USER_AGENT,
    "Accept": "application/json",
    "Origin": "https://money.tmx.com",
    "Referer": "https://money.tmx.com/",
}

_SESSION = requests.Session()
_SESSION.headers.update(
    {
        "User-Agent": USER_AGENT,
        "Accept": "application/json,text/plain,*/*",
        "Accept-Language": "en-CA,en;q=0.9",
    }
)
_CACHED_CRUMB: str | None = None
_LAST_FETCH_ERROR: str | None = None

_CACHE_LOCK = threading.Lock()
_QUOTE_CACHE: dict[tuple[str, str], tuple[float, dict[str, Any]]] = {}
_FUND_CACHE: dict[str, tuple[float, dict[str, Any]]] = {}
_MACRO_CACHE: tuple[float, dict[str, float | None]] | None = None


def _safe_float(val: Any) -> float | None:
    if val is None or val == "":
        return None
    try:
        out = float(val)
        return out if math.isfinite(out) else None
    except (TypeError, ValueError):
        return None


def _to_tmx_symbol(symbol: str) -> str:
    s = symbol.strip().upper()
    if s == "^GSPTSE":
        return "^TSX"
    for suffix in (".TO", ".V", ".CN", ".NE"):
        if s.endswith(suffix):
            s = s[: -len(suffix)]
            break
    if "-" in s and not s.startswith("^"):
        s = s.replace("-", ".")
    return s


def _tmx_graphql(query: str, variables: dict[str, Any] | None = None, timeout: int = 10) -> dict[str, Any] | None:
    payload: dict[str, Any] = {"query": query}
    if variables:
        payload["variables"] = variables
    resp = _SESSION.post(TMX_GRAPHQL_URL, json=payload, headers=_TMX_HEADERS, timeout=timeout)
    if resp.status_code != 200:
        raise RuntimeError(f"TMX HTTP {resp.status_code}")
    data = resp.json()
    return data.get("data")


def _tmx_fundamentals_from_quote(qdata: dict[str, Any]) -> dict[str, Any]:
    div_y = _safe_float(qdata.get("dividendYield"))
    roe = _safe_float(qdata.get("returnOnEquity"))
    dte = _safe_float(qdata.get("totalDebtToEquity"))
    return {
        "sector": qdata.get("sector") or None,
        "industry": qdata.get("industry") or None,
        "summary": qdata.get("shortDescription") or None,
        "market_cap": _safe_float(qdata.get("MarketCap")),
        "trailing_pe": _safe_float(qdata.get("peRatio")),
        "forward_pe": None,
        "price_to_book": _safe_float(qdata.get("priceToBook")),
        "peg_ratio": None,
        "dividend_yield": (div_y / 100.0) if div_y is not None else None,
        "payout_ratio": None,
        "beta": _safe_float(qdata.get("beta")),
        "roe": (roe / 100.0) if roe is not None else None,
        "operating_margin": None,
        "profit_margin": None,
        "debt_to_equity": (dte * 100.0) if dte is not None else None,
        "current_ratio": None,
        "free_cash_flow": None,
        "revenue_growth": None,
        "target_mean": None,
        "eps": _safe_float(qdata.get("eps")),
    }


def _fetch_tmx_quote_and_chart(symbol: str, chart_range: str = "1y") -> dict[str, Any] | None:
    tmx_sym = _to_tmx_symbol(symbol)
    quote_gql = """
    query getQuoteBySymbol($symbol: String, $locale: String) {
      getQuoteBySymbol(symbol: $symbol, locale: $locale) {
        symbol
        name
        price
        priceChange
        percentChange
        exchangeName
        exShortName
        volume
        openPrice
        dayHigh
        dayLow
        MarketCap
        peRatio
        prevClose
        dividendYield
        dividendAmount
        weeks52high
        weeks52low
        beta
        sector
        industry
        shortDescription
        priceToBook
        eps
        returnOnEquity
        totalDebtToEquity
      }
    }
    """
    q_res = _tmx_graphql(quote_gql, {"symbol": tmx_sym, "locale": "en"})
    qdata = (q_res or {}).get("getQuoteBySymbol")
    if not qdata or _safe_float(qdata.get("price")) is None:
        return None

    with _CACHE_LOCK:
        _FUND_CACHE[symbol.strip().upper()] = (time.time(), _tmx_fundamentals_from_quote(qdata))

    now_dt = datetime.now(timezone.utc)
    end_str = now_dt.strftime("%Y-%m-%d")
    range_days = {
        "5d": 14,
        "1mo": 35,
        "3mo": 95,
        "6mo": 185,
        "1y": 370,
        "5y": 5 * 366,
    }.get(chart_range, 370)
    start_str = (now_dt - timedelta(days=range_days)).strftime("%Y-%m-%d")

    ts_gql = """
    query getTimeSeriesData($symbol: String!, $freq: String, $start: String, $end: String) {
      getTimeSeriesData(symbol: $symbol, freq: $freq, start: $start, end: $end) {
        dateTime
        open
        high
        low
        close
        volume
      }
    }
    """
    freq_order = ["minute", "day"] if chart_range == "5d" else (["week", "day"] if chart_range == "5y" else ["day"])
    bars: list[dict[str, Any]] = []
    for freq in freq_order:
        ts_start = (now_dt - timedelta(days=10)).strftime("%Y-%m-%d") if freq == "minute" else start_str
        ts_res = _tmx_graphql(ts_gql, {"symbol": tmx_sym, "freq": freq, "start": ts_start, "end": end_str})
        cand = (ts_res or {}).get("getTimeSeriesData") or []
        if len(cand) >= (8 if chart_range == "5d" else 5):
            bars = cand
            break
        if len(cand) > len(bars):
            bars = cand

    if len(bars) < 2:
        return None

    df = pd.DataFrame(bars)
    df["date"] = pd.to_datetime(df["dateTime"], utc=True).dt.tz_convert("America/Toronto")
    for col in ("open", "high", "low", "close", "volume"):
        df[col] = pd.to_numeric(df[col], errors="coerce")
    df = df.dropna(subset=["close"]).sort_values("date").reset_index(drop=True)
    if len(df) < 2:
        return None

    df["open"] = df["open"].fillna(df["close"])
    df["high"] = df["high"].fillna(df[["open", "close"]].max(axis=1))
    df["low"] = df["low"].fillna(df[["open", "close"]].min(axis=1))
    df["volume"] = df["volume"].fillna(0.0)

    price = _safe_float(qdata.get("price")) or float(df["close"].iloc[-1])
    change = _safe_float(qdata.get("priceChange"))
    prev_close = _safe_float(qdata.get("prevClose"))
    if prev_close is None and change is not None:
        prev_close = price - change
    if prev_close is None:
        prev_close = float(df["close"].iloc[-2])
    if change is None:
        change = price - prev_close
    change_pct = _safe_float(qdata.get("percentChange"))
    if change_pct is None:
        change_pct = (change / prev_close) * 100.0 if prev_close > 0 else 0.0

    raw_ex = (qdata.get("exShortName") or "").upper()
    canon_sym = symbol.strip().upper()
    if canon_sym == "^GSPTSE" or tmx_sym == "^TSX":
        exch = "TSX Index"
    elif "TSXV" in raw_ex or canon_sym.endswith(".V"):
        exch = "TSXV"
    else:
        exch = raw_ex or "TSX"

    return {
        "symbol": canon_sym,
        "name": qdata.get("name") or canon_sym,
        "exchange": exch,
        "currency": "CAD",
        "price": float(price),
        "prev_close": float(prev_close),
        "change": float(change),
        "change_pct": float(change_pct),
        "high_52w": _safe_float(qdata.get("weeks52high")) or float(df["high"].max()),
        "low_52w": _safe_float(qdata.get("weeks52low")) or float(df["low"].min()),
        "candles": df[["date", "open", "high", "low", "close", "volume"]],
    }


def _get_yahoo_crumb(force: bool = False) -> str | None:
    global _CACHED_CRUMB
    if not force and _CACHED_CRUMB:
        return _CACHED_CRUMB
    try:
        _SESSION.get("https://fc.yahoo.com", timeout=6)
    except Exception:
        pass
    try:
        resp = _SESSION.get("https://query1.finance.yahoo.com/v1/test/getcrumb", timeout=6)
        if resp.status_code == 200:
            txt = resp.text.strip()
            if txt and not txt.startswith("{"):
                _CACHED_CRUMB = txt
    except Exception:
        pass
    return _CACHED_CRUMB


def _fetch_yfinance_chart(symbol: str, chart_range: str = "1y", interval: str = "1d") -> dict[str, Any] | None:
    if yf is None:
        return None
    try:
        tkr = yf.Ticker(symbol.strip())
        hist = tkr.history(period=chart_range, interval=interval, auto_adjust=False)
        if hist is None or hist.empty or len(hist) < 2:
            return None
        hist = hist.reset_index()
        date_col = "Datetime" if "Datetime" in hist.columns else "Date"
        dt = pd.to_datetime(hist[date_col])
        if dt.dt.tz is None:
            dt = dt.dt.tz_localize("America/Toronto")
        else:
            dt = dt.dt.tz_convert("America/Toronto")
        df = pd.DataFrame(
            {
                "date": dt,
                "open": pd.to_numeric(hist["Open"], errors="coerce"),
                "high": pd.to_numeric(hist["High"], errors="coerce"),
                "low": pd.to_numeric(hist["Low"], errors="coerce"),
                "close": pd.to_numeric(hist["Close"], errors="coerce"),
                "volume": pd.to_numeric(hist["Volume"], errors="coerce").fillna(0.0),
            }
        ).dropna(subset=["close"])
        if len(df) < 2:
            return None
        df["open"] = df["open"].fillna(df["close"])
        df["high"] = df["high"].fillna(df[["open", "close"]].max(axis=1))
        df["low"] = df["low"].fillna(df[["open", "close"]].min(axis=1))

        fi = getattr(tkr, "fast_info", None)
        price = _safe_float(getattr(fi, "last_price", None)) or float(df["close"].iloc[-1])
        prev_close = _safe_float(getattr(fi, "previous_close", None)) or float(df["close"].iloc[-2])
        change = price - prev_close
        change_pct = (change / prev_close) * 100.0 if prev_close > 0 else 0.0
        sym = symbol.strip().upper()
        exch = "TSXV" if sym.endswith(".V") else ("TSX Index" if sym == "^GSPTSE" else "TSX")
        return {
            "symbol": sym,
            "name": SYMBOL_CHOICES.get(sym, sym).split(" — ")[-1],
            "exchange": exch,
            "currency": "CAD",
            "price": float(price),
            "prev_close": float(prev_close),
            "change": float(change),
            "change_pct": float(change_pct),
            "high_52w": float(df["high"].max()),
            "low_52w": float(df["low"].min()),
            "candles": df.reset_index(drop=True),
        }
    except Exception:
        return None


def _fetch_yahoo_direct_chart(symbol: str, chart_range: str = "1y", interval: str = "1d") -> dict[str, Any] | None:
    encoded = quote_plus(symbol.strip())
    urls = [
        f"https://query1.finance.yahoo.com/v8/finance/chart/{encoded}?interval={interval}&range={chart_range}&includePrePost=false",
        f"https://query2.finance.yahoo.com/v8/finance/chart/{encoded}?interval={interval}&range={chart_range}&includePrePost=false",
    ]
    for url in urls:
        try:
            resp = _SESSION.get(url, timeout=8)
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

            sym = (meta.get("symbol") or symbol).upper()
            exch = "TSXV" if sym.endswith(".V") else ("TSX Index" if sym == "^GSPTSE" else "TSX")
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
                "candles": df[["date", "open", "high", "low", "close", "volume"]].reset_index(drop=True),
            }
        except Exception:
            continue
    return None


def fetch_chart_quote(
    symbol: str,
    chart_range: str = "1y",
    interval: str = "1d",
    force_refresh: bool = False,
) -> dict[str, Any] | None:
    global _LAST_FETCH_ERROR
    key = (symbol.strip().upper(), chart_range)
    now = time.time()
    if not force_refresh:
        with _CACHE_LOCK:
            cached = _QUOTE_CACHE.get(key)
            if cached and (now - cached[0]) < 45.0:
                return cached[1]

    errs: list[str] = []
    out = None
    try:
        out = _fetch_tmx_quote_and_chart(symbol, chart_range)
        if out is None:
            errs.append("TMX returned empty series")
    except Exception as exc:
        errs.append(f"TMX: {exc}")

    if out is None:
        try:
            out = _fetch_yfinance_chart(symbol, chart_range, interval)
            if out is None:
                errs.append("yfinance unavailable/empty")
        except Exception as exc:
            errs.append(f"yfinance: {exc}")

    if out is None:
        out = _fetch_yahoo_direct_chart(symbol, chart_range, interval)
        if out is None:
            errs.append("Yahoo direct rate-limited/empty")

    if out is not None:
        _LAST_FETCH_ERROR = None
        with _CACHE_LOCK:
            _QUOTE_CACHE[key] = (now, out)
    else:
        _LAST_FETCH_ERROR = "; ".join(errs) if errs else "All market feeds failed"
    return out


def fetch_fundamentals(symbol: str) -> dict[str, Any] | None:
    sym_key = symbol.strip().upper()
    now = time.time()
    with _CACHE_LOCK:
        cached = _FUND_CACHE.get(sym_key)
        if cached and (now - cached[0]) < 600.0:
            return cached[1]

    # Try TMX quote fundamentals first
    try:
        tmx_sym = _to_tmx_symbol(sym_key)
        q_res = _tmx_graphql(
            """
            query getQuoteBySymbol($symbol: String, $locale: String) {
              getQuoteBySymbol(symbol: $symbol, locale: $locale) {
                MarketCap peRatio dividendYield beta sector industry
                shortDescription priceToBook eps returnOnEquity totalDebtToEquity
              }
            }
            """,
            {"symbol": tmx_sym, "locale": "en"},
        )
        qdata = (q_res or {}).get("getQuoteBySymbol")
        if qdata:
            fund_obj = _tmx_fundamentals_from_quote(qdata)
            with _CACHE_LOCK:
                _FUND_CACHE[sym_key] = (now, fund_obj)
            return fund_obj
    except Exception:
        pass

    # Secondary fallback via Yahoo Finance direct quoteSummary
    encoded = quote_plus(symbol.strip())
    modules = "summaryDetail,defaultKeyStatistics,financialData,assetProfile,price"
    for attempt in range(2):
        crumb = _get_yahoo_crumb(force=(attempt == 1))
        crumb_q = f"&crumb={quote_plus(crumb)}" if crumb else ""
        url = f"https://query1.finance.yahoo.com/v10/finance/quoteSummary/{encoded}?modules={modules}{crumb_q}"
        try:
            resp = _SESSION.get(url, timeout=8)
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

            fund_obj = {
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
                "eps": raw_val(ks, "trailingEps"),
            }
            with _CACHE_LOCK:
                _FUND_CACHE[sym_key] = (now, fund_obj)
            return fund_obj
        except Exception:
            continue
    return None


def fetch_boc_macro() -> dict[str, float | None]:
    global _MACRO_CACHE
    now = time.time()
    if _MACRO_CACHE and (now - _MACRO_CACHE[0]) < 600.0:
        return _MACRO_CACHE[1]
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
        res = {"cad_usd": cad_usd, "boc_rate": boc_rate}
        _MACRO_CACHE = (now, res)
        return res
    except Exception:
        return {"cad_usd": None, "boc_rate": None}


def search_canadian_tickers(query: str) -> list[tuple[str, str]]:
    q_clean = query.strip()
    if not q_clean:
        return []
    q_upper = q_clean.upper()
    out: list[tuple[str, str]] = []
    seen: set[str] = set()

    # 1. Exact/direct lookup on TMX GraphQL (works for unlisted TSX/TSXV tickers like L.TO, T.TO, AC.TO)
    try:
        tmx_sym = _to_tmx_symbol(q_upper)
        q_res = _tmx_graphql(
            """
            query getQuoteBySymbol($symbol: String, $locale: String) {
              getQuoteBySymbol(symbol: $symbol, locale: $locale) {
                symbol name exShortName price
              }
            }
            """,
            {"symbol": tmx_sym, "locale": "en"},
        )
        qdata = (q_res or {}).get("getQuoteBySymbol")
        if qdata and qdata.get("symbol"):
            ex = (qdata.get("exShortName") or "TSX").upper()
            suffix = ".V" if "TSXV" in ex else ".TO"
            canon = q_upper if q_upper.endswith((".TO", ".V", ".CN", ".NE")) or q_upper.startswith("^") else f"{qdata['symbol']}{suffix}"
            out.append((canon, qdata.get("name") or canon))
            seen.add(canon)
    except Exception:
        pass

    # 2. Multi-token match across full TMX/TSXV directory (3,700+ listed issuers)
    tokens = [t for t in q_upper.replace(",", " ").split() if t]
    for _, r in DIRECTORY_DF.iterrows():
        hay = f"{r['symbol']} {r['name']} {r['sector']}".upper()
        if all(tok in hay for tok in tokens):
            if r["symbol"] not in seen:
                out.append((r["symbol"], r["name"]))
                seen.add(r["symbol"])
                if len(out) >= 40:
                    break

    # 3. Typo-tolerant fuzzy match (e.g. "nvdia cdr" -> "Nvidia CDR (CAD Hedged)")
    if not out and tokens:
        scored: list[tuple[float, str, str]] = []
        for _, r in DIRECTORY_DF.iterrows():
            words = f"{r['symbol'].split('.')[0]} {r['name']}".upper().split()
            token_scores = [
                max((difflib.SequenceMatcher(None, tok, w).ratio() for w in words), default=0.0)
                for tok in tokens
            ]
            avg_s = sum(token_scores) / len(token_scores)
            if min(token_scores) >= 0.72 and avg_s >= 0.78:
                scored.append((avg_s, r["symbol"], r["name"]))
        scored.sort(reverse=True)
        for _, sym, nm in scored[:25]:
            if sym not in seen:
                out.append((sym, nm))
                seen.add(sym)

    # 3. Yahoo Finance search bonus (if not rate-limited)
    if not out:
        try:
            url = (
                f"https://query1.finance.yahoo.com/v1/finance/search?q={quote_plus(q_clean)}"
                "&quotesCount=15&newsCount=4&region=CA&lang=en-CA"
            )
            resp = _SESSION.get(url, timeout=6)
            if resp.status_code == 200:
                for q in resp.json().get("quotes") or []:
                    sym = (q.get("symbol") or "").upper()
                    exch = q.get("exchange", "")
                    if sym.endswith((".TO", ".V", ".CN", ".NE")) or sym == "^GSPTSE" or exch in ("TOR", "VAN"):
                        if sym not in seen:
                            nm = q.get("shortname") or q.get("longname") or sym
                            out.append((sym, nm))
                            seen.add(sym)
        except Exception:
            pass
    return out


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
.selectize-input { background-color: #0B1622 !important; color: #F1F5F9 !important; border-color: #233B54 !important; }
.selectize-input input { color: #F1F5F9 !important; }
.selectize-dropdown { background-color: #102030 !important; color: #F1F5F9 !important; border-color: #233B54 !important; }
.selectize-dropdown .option { color: #F1F5F9 !important; }
.selectize-dropdown .active { background-color: #162A3E !important; color: #10B981 !important; }
"""

app_ui = ui.page_navbar(
    ui.nav_panel(
        "Markets & Analyzer",
        ui.layout_sidebar(
            ui.sidebar(
                ui.input_selectize(
                    "selected_symbol",
                    f"Active Canadian Equity ({len(SYMBOL_CHOICES):,} TSX/TSXV)",
                    choices=SYMBOL_CHOICES,
                    selected="RY.TO",
                    options={"maxOptions": 5000, "placeholder": "Backspace & type ticker/name (e.g. NVDA, RY)..."},
                ),
                ui.div("Tip: Backspace in selector above or search below.", class_="small text-secondary mb-2"),
                ui.input_text("custom_search", "Search Any TSX / TSXV / CDR", placeholder="e.g. nvidia cdr, L.TO, CTC.A, ONE"),
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
                ui.input_select(
                    "screener_scope",
                    "Universe Scope",
                    choices={
                        "CURATED": "Curated TSX/TSXV Blue-Chips (24)",
                        "CDR": "Canadian CDRs (CAD Hedged)",
                        "TSX": "All TSX Directory (Top 40 Match)",
                        "TSXV": "All TSXV Directory (Top 40 Match)",
                    },
                ),
                ui.input_select("screener_sector", "Curated Sector", choices=["All"] + sorted(TSX_UNIVERSE["sector"].unique().tolist())),
                ui.input_text("screener_keyword", "Keyword / Ticker Filter", placeholder="Optional (e.g. Gold, Bank, .UN)"),
                ui.input_select(
                    "screener_filter",
                    "Signal Filter",
                    choices=["All", "Bullish (Buy / Strong Buy)", "Oversold (RSI <= 35)", "Overbought (RSI >= 70)"],
                ),
                col_widths=[3, 3, 3, 3],
            ),
            ui.input_action_button("run_screener_btn", "Scan Live TSX / TSXV Universe", class_="btn-success mt-2"),
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
                ui.input_selectize(
                    "alert_symbol",
                    f"Ticker ({len(SYMBOL_CHOICES):,} TSX/TSXV)",
                    choices=SYMBOL_CHOICES,
                    selected="RY.TO",
                    options={"maxOptions": 5000, "placeholder": "Backspace & type ticker/name..."},
                ),
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
    last_refresh_n = reactive.value(0)

    @reactive.calc
    def active_data():
        refresh_n = input.refresh_symbol_btn()
        sym = input.selected_symbol()
        rng = input.chart_range()
        interval = "15m" if rng == "5d" else ("1wk" if rng == "5y" else "1d")

        is_forced = False
        with reactive.isolate():
            if refresh_n > last_refresh_n():
                last_refresh_n.set(refresh_n)
                is_forced = True
                macro_rv.set(fetch_boc_macro())
                tsx_index_rv.set(fetch_chart_quote("^GSPTSE", "3mo", "1d", force_refresh=True))

        quote_obj = fetch_chart_quote(sym, rng, interval, force_refresh=is_forced)
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
                if sym not in merged:
                    merged[sym] = f"{sym} — {nm}"
            ui.update_selectize("selected_symbol", choices=merged, selected=hits[0][0])
            ui.update_selectize("alert_symbol", choices=merged, selected=hits[0][0])
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
            detail = f" ({_LAST_FETCH_ERROR})" if _LAST_FETCH_ERROR else ""
            return ui.div(f"Unable to load live TSX quote{detail}. Click Refresh Live Quote to retry.", class_="alert alert-danger")
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

        def fmt_cap(v: float | None) -> str:
            if v is None:
                return "--"
            if v >= 1e12:
                return f"${v / 1e12:.2f}T"
            if v >= 1e9:
                return f"${v / 1e9:.2f}B"
            if v >= 1e6:
                return f"${v / 1e6:.1f}M"
            return f"${v:,.0f}"

        upside_node = (
            ui.span(
                f"Analyst Target Upside: {ev['upside_pct']:+.1f}%",
                class_="bull-txt mono-num" if ev["upside_pct"] >= 0 else "bear-txt mono-num",
            )
            if ev["upside_pct"] is not None
            else ui.span(f"Market Cap: {fmt_cap(f.get('market_cap'))}", class_="cyan-txt mono-num")
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
                ui.div(ui.div("EPS (TTM)", class_="small text-secondary"), ui.h5(fmt(f.get("eps")), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("Dividend Yield", class_="small text-secondary"), ui.h5(fmt(f.get("dividend_yield"), 100.0, "%"), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("ROE", class_="small text-secondary"), ui.h5(fmt(f.get("roe"), 100.0, "%"), class_="mono-num"), class_="metric-box"),
                col_widths=[3, 3, 3, 3],
            ),
            ui.br(),
            ui.layout_columns(
                ui.div(ui.div("Price / Book", class_="small text-secondary"), ui.h5(fmt(f.get("price_to_book")), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("Beta (5Y)", class_="small text-secondary"), ui.h5(fmt(f.get("beta")), class_="mono-num"), class_="metric-box"),
                ui.div(ui.div("Debt / Equity", class_="small text-secondary"), ui.h5(fmt(f.get("debt_to_equity"), 1.0, "%"), class_="mono-num"), class_="metric-box"),
                ui.div(
                    ui.div("Analyst Target" if f.get("target_mean") is not None else "52W Low / High", class_="small text-secondary"),
                    ui.h5(
                        fmt(f.get("target_mean"))
                        if f.get("target_mean") is not None
                        else f"${dat['quote']['low_52w']:.2f} / ${dat['quote']['high_52w']:.2f}",
                        class_="mono-num",
                    ),
                    class_="metric-box",
                ),
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
        scope = input.screener_scope()
        if scope == "CURATED":
            df_u = TSX_UNIVERSE.copy()
            if input.screener_sector() != "All":
                df_u = df_u[df_u["sector"] == input.screener_sector()]
        elif scope == "CDR":
            df_u = DIRECTORY_DF[DIRECTORY_DF["sector"] == "CDR (CAD Hedged)"].copy()
        else:
            df_u = DIRECTORY_DF[DIRECTORY_DF["exchange"] == scope].copy()

        kw = (input.screener_keyword() or "").strip().upper()
        if kw:
            mask = (
                df_u["symbol"].str.upper().str.contains(kw, regex=False, na=False)
                | df_u["name"].str.upper().str.contains(kw, regex=False, na=False)
            )
            df_u = df_u[mask]
        df_u = df_u.head(40)

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
