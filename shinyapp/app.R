# ==============================================================================
# MaplePulse — Canadian TSX / TSXV Equity Intelligence (R Shiny for shinyapps.io)
# ==============================================================================
# Deploy to shinyapps.io using:
#   rsconnect::deployApp("shinyapp", appName = "maplepulse-tsx")
# ==============================================================================

library(shiny)
library(bslib)
library(httr)
library(jsonlite)
library(TTR)
library(plotly)
library(forecast)
library(DT)

# ------------------------------------------------------------------------------
# Canadian Equity Universe & Macro Helpers
# ------------------------------------------------------------------------------

TSX_UNIVERSE <- data.frame(
  symbol = c(
    "RY.TO", "TD.TO", "SHOP.TO", "ENB.TO", "CNQ.TO", "CSU.TO",
    "BN.TO", "CP.TO", "CNR.TO", "ATD.TO", "BMO.TO", "BNS.TO",
    "MFC.TO", "ABX.TO", "AEM.TO", "NTR.TO", "BCE.TO", "FTS.TO",
    "DOL.TO", "SU.TO", "XIU.TO", "XEQT.TO", "HIVE.V", "NGD.TO"
  ),
  name = c(
    "Royal Bank of Canada", "Toronto-Dominion Bank", "Shopify Inc.",
    "Enbridge Inc.", "Canadian Natural Resources", "Constellation Software",
    "Brookfield Corp.", "Canadian Pacific Kansas City", "Canadian National Railway",
    "Alimentation Couche-Tard", "Bank of Montreal", "Bank of Nova Scotia",
    "Manulife Financial", "Barrick Gold Corp.", "Agnico Eagle Mines",
    "Nutrien Ltd.", "BCE Inc.", "Fortis Inc.",
    "Dollarama Inc.", "Suncor Energy Inc.", "iShares S&P/TSX 60 ETF",
    "iShares Core Equity ETF", "HIVE Digital Technologies", "New Gold Inc."
  ),
  exchange = c(
    rep("TSX", 22), "TSXV", "TSX"
  ),
  sector = c(
    "Financials", "Financials", "Technology", "Energy", "Energy", "Technology",
    "Financials", "Industrials", "Industrials", "Consumer", "Financials", "Financials",
    "Financials", "Materials & Gold", "Materials & Gold", "Materials & Gold",
    "Telecom & Utilities", "Telecom & Utilities", "Consumer", "Energy",
    "ETFs", "ETFs", "Technology", "Materials & Gold"
  ),
  stringsAsFactors = FALSE
)

USER_AGENT <- "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

yahoo_handle <- httr::handle("https://query1.finance.yahoo.com")
yahoo_crumb_cache <- new.env(parent = emptyenv())
yahoo_crumb_cache$crumb <- NULL

get_yahoo_crumb <- function(force = FALSE) {
  if (!force && !is.null(yahoo_crumb_cache$crumb) && nzchar(yahoo_crumb_cache$crumb)) {
    return(yahoo_crumb_cache$crumb)
  }
  tryCatch({
    httr::GET("https://fc.yahoo.com", handle = yahoo_handle, httr::user_agent(USER_AGENT), httr::timeout(8))
    res <- httr::GET(
      "https://query1.finance.yahoo.com/v1/test/getcrumb",
      handle = yahoo_handle,
      httr::user_agent(USER_AGENT),
      httr::timeout(8)
    )
    if (httr::status_code(res) == 200) {
      txt <- trimws(httr::content(res, as = "text", encoding = "UTF-8"))
      if (nzchar(txt) && !startsWith(txt, "{")) {
        yahoo_crumb_cache$crumb <- txt
      }
    }
  }, error = function(e) NULL)
  yahoo_crumb_cache$crumb
}

fetch_chart_quote <- function(symbol, range = "1y", interval = "1d") {
  encoded <- utils::URLencode(trimws(symbol), reserved = TRUE)
  urls <- c(
    sprintf("https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=%s&range=%s&includePrePost=false", encoded, interval, range),
    sprintf("https://query2.finance.yahoo.com/v8/finance/chart/%s?interval=%s&range=%s&includePrePost=false", encoded, interval, range)
  )

  for (u in urls) {
    out <- tryCatch({
      res <- httr::GET(u, httr::user_agent(USER_AGENT), httr::timeout(10))
      if (httr::status_code(res) != 200) return(NULL)
      parsed <- jsonlite::fromJSON(httr::content(res, as = "text", encoding = "UTF-8"), simplifyVector = FALSE)
      result <- parsed$chart$result[[1]]
      if (is.null(result)) return(NULL)

      meta <- result$meta
      ts <- unlist(result$timestamp)
      q <- result$indicators$quote[[1]]
      n <- length(ts)
      if (n == 0) return(NULL)

      num_vec <- function(lst, fallback = NA_real_) {
        vapply(seq_len(n), function(i) {
          val <- lst[[i]]
          if (is.null(val)) fallback else as.numeric(val)
        }, numeric(1))
      }

      close_v <- num_vec(q$close)
      valid <- !is.na(close_v) & close_v > 0
      if (sum(valid) < 2) return(NULL)

      df <- data.frame(
        date = as.POSIXct(ts[valid], origin = "1970-01-01", tz = "America/Toronto"),
        open = num_vec(q$open)[valid],
        high = num_vec(q$high)[valid],
        low = num_vec(q$low)[valid],
        close = close_v[valid],
        volume = num_vec(q$volume, 0)[valid]
      )
      df$open[is.na(df$open)] <- df$close[is.na(df$open)]
      df$high[is.na(df$high)] <- pmax(df$open[is.na(df$high)], df$close[is.na(df$high)])
      df$low[is.na(df$low)] <- pmin(df$open[is.na(df$low)], df$close[is.na(df$low)])

      price <- as.numeric(meta$regularMarketPrice %||% tail(df$close, 1))
      prev_close <- as.numeric(meta$chartPreviousClose %||% meta$previousClose %||% df$close[max(1, nrow(df) - 1)])
      change <- price - prev_close
      change_pct <- if (!is.na(prev_close) && prev_close > 0) (change / prev_close) * 100 else 0

      list(
        symbol = meta$symbol %||% symbol,
        name = meta$longName %||% meta$shortName %||% symbol,
        exchange = if (grepl("\\.V$", symbol, ignore.case = TRUE)) "TSXV" else if (symbol == "^GSPTSE") "TSX Index" else "TSX",
        currency = meta$currency %||% "CAD",
        price = price,
        prev_close = prev_close,
        change = change,
        change_pct = change_pct,
        high_52w = as.numeric(meta$fiftyTwoWeekHigh %||% max(df$high, na.rm = TRUE)),
        low_52w = as.numeric(meta$fiftyTwoWeekLow %||% min(df$low, na.rm = TRUE)),
        volume = as.numeric(meta$regularMarketVolume %||% tail(df$volume, 1)),
        candles = df
      )
    }, error = function(e) NULL)
    if (!is.null(out)) return(out)
  }
  NULL
}

`%||%` <- function(a, b) if (is.null(a) || length(a) == 0 || (is.character(a) && !nzchar(a))) b else a

fetch_fundamentals <- function(symbol) {
  encoded <- utils::URLencode(trimws(symbol), reserved = TRUE)
  modules <- "summaryDetail,defaultKeyStatistics,financialData,assetProfile,price"
  for (attempt in 1:2) {
    crumb <- get_yahoo_crumb(force = (attempt == 2))
    crumb_q <- if (!is.null(crumb)) paste0("&crumb=", utils::URLencode(crumb, reserved = TRUE)) else ""
    url <- sprintf("https://query1.finance.yahoo.com/v10/finance/quoteSummary/%s?modules=%s%s", encoded, modules, crumb_q)
    res <- tryCatch(
      httr::GET(url, handle = yahoo_handle, httr::user_agent(USER_AGENT), httr::timeout(10)),
      error = function(e) NULL
    )
    if (!is.null(res) && httr::status_code(res) == 200) {
      parsed <- tryCatch(
        jsonlite::fromJSON(httr::content(res, as = "text", encoding = "UTF-8"), simplifyVector = FALSE),
        error = function(e) NULL
      )
      r <- parsed$quoteSummary$result[[1]]
      if (!is.null(r)) {
        raw_num <- function(obj, key) {
          val <- obj[[key]]$raw
          if (is.null(val)) NA_real_ else as.numeric(val)
        }
        sd <- r$summaryDetail
        ks <- r$defaultKeyStatistics
        fd <- r$financialData
        ap <- r$assetProfile
        pr <- r$price
        return(list(
          sector = ap$sector %||% NA_character_,
          industry = ap$industry %||% NA_character_,
          summary = ap$longBusinessSummary %||% NA_character_,
          market_cap = raw_num(pr, "marketCap") %||% raw_num(sd, "marketCap"),
          trailing_pe = raw_num(sd, "trailingPE"),
          forward_pe = raw_num(sd, "forwardPE"),
          price_to_book = raw_num(ks, "priceToBook"),
          peg_ratio = raw_num(ks, "pegRatio"),
          dividend_yield = raw_num(sd, "dividendYield"),
          payout_ratio = raw_num(sd, "payoutRatio"),
          beta = raw_num(sd, "beta"),
          roe = raw_num(fd, "returnOnEquity"),
          operating_margin = raw_num(fd, "operatingMargins"),
          profit_margin = raw_num(fd, "profitMargins"),
          debt_to_equity = raw_num(fd, "debtToEquity"),
          current_ratio = raw_num(fd, "currentRatio"),
          free_cash_flow = raw_num(fd, "freeCashflow"),
          revenue_growth = raw_num(fd, "revenueGrowth"),
          target_mean = raw_num(fd, "targetMeanPrice"),
          recommendation = fd$recommendationKey %||% NA_character_
        ))
      }
    }
  }
  NULL
}

fetch_boc_macro <- function() {
  tryCatch({
    res <- httr::GET("https://www.bankofcanada.ca/valet/observations/FXCADUSD,V39079/json?recent=3", httr::timeout(8))
    if (httr::status_code(res) != 200) return(list(cad_usd = NA_real_, boc_rate = NA_real_))
    parsed <- jsonlite::fromJSON(httr::content(res, as = "text", encoding = "UTF-8"), simplifyVector = FALSE)
    obs <- parsed$observations
    cad_usd <- NA_real_
    boc_rate <- NA_real_
    for (row in rev(obs)) {
      if (is.na(cad_usd) && !is.null(row$FXCADUSD$v)) cad_usd <- as.numeric(row$FXCADUSD$v)
      if (is.na(boc_rate) && !is.null(row$V39079$v)) boc_rate <- as.numeric(row$V39079$v)
    }
    list(cad_usd = cad_usd, boc_rate = boc_rate)
  }, error = function(e) list(cad_usd = NA_real_, boc_rate = NA_real_))
}

search_canadian_tickers <- function(query) {
  q <- utils::URLencode(trimws(query), reserved = TRUE)
  url <- sprintf("https://query1.finance.yahoo.com/v1/finance/search?q=%s&quotesCount=15&newsCount=5&region=CA&lang=en-CA", q)
  tryCatch({
    res <- httr::GET(url, httr::user_agent(USER_AGENT), httr::timeout(8))
    if (httr::status_code(res) != 200) return(data.frame())
    parsed <- jsonlite::fromJSON(httr::content(res, as = "text", encoding = "UTF-8"), simplifyVector = FALSE)
    quotes <- parsed$quotes
    rows <- Filter(function(x) {
      sym <- x$symbol %||% ""
      exch <- x$exchange %||% ""
      grepl("\\.(TO|V|CN|NE)$", sym, ignore.case = TRUE) || sym == "^GSPTSE" || exch %in% c("TOR", "VAN")
    }, quotes)
    if (length(rows) == 0) return(data.frame())
    data.frame(
      symbol = vapply(rows, function(x) x$symbol %||% "", character(1)),
      name = vapply(rows, function(x) x$shortname %||% x$longname %||% x$symbol %||% "", character(1)),
      exchange = vapply(rows, function(x) if (grepl("\\.V$", x$symbol %||% "")) "TSXV" else "TSX", character(1)),
      stringsAsFactors = FALSE
    )
  }, error = function(e) data.frame())
}

# ------------------------------------------------------------------------------
# Quantitative Analysis Engines (Technical, Fundamental, Forecasting)
# ------------------------------------------------------------------------------

compute_technicals <- function(quote_obj) {
  df <- quote_obj$candles
  n <- nrow(df)
  closes <- df$close

  sma20 <- if (n >= 20) TTR::SMA(closes, 20) else rep(NA_real_, n)
  sma50 <- if (n >= 50) TTR::SMA(closes, 50) else rep(NA_real_, n)
  sma200 <- if (n >= 200) TTR::SMA(closes, 200) else rep(NA_real_, n)
  rsi14 <- if (n >= 16) TTR::RSI(closes, 14) else rep(NA_real_, n)
  macd_mat <- if (n >= 35) TTR::MACD(closes, 12, 26, 9) else matrix(NA_real_, nrow = n, ncol = 2)
  bb_mat <- if (n >= 20) TTR::BBands(df[, c("high", "low", "close")], n = 20, sd = 2) else matrix(NA_real_, nrow = n, ncol = 4)
  atr14 <- if (n >= 16) TTR::ATR(df[, c("high", "low", "close")], n = 14)[, "atr"] else rep(NA_real_, n)

  log_ret <- diff(log(closes))
  ann_vol <- if (length(log_ret) >= 20) stats::sd(tail(log_ret, 20)) * sqrt(252) * 100 else NA_real_

  price <- quote_obj$price
  l_sma20 <- tail(sma20, 1)
  l_sma50 <- tail(sma50, 1)
  l_sma200 <- tail(sma200, 1)
  l_rsi <- tail(rsi14, 1)
  l_macd <- tail(macd_mat[, "macd"], 1)
  l_sig <- tail(macd_mat[, "signal"], 1)
  l_bb_up <- tail(bb_mat[, "up"], 1)
  l_bb_dn <- tail(bb_mat[, "dn"], 1)
  l_atr <- tail(atr14, 1)

  biases <- c()
  if (!is.na(l_sma20)) biases <- c(biases, if (price > l_sma20) 1 else -1)
  if (!is.na(l_sma50)) biases <- c(biases, if (price > l_sma50) 1 else -1)
  if (!is.na(l_sma50) && !is.na(l_sma200)) biases <- c(biases, if (l_sma50 > l_sma200) 1 else -1)
  if (!is.na(l_rsi)) biases <- c(biases, if (l_rsi < 35) 1 else if (l_rsi > 70) -1 else 0)
  if (!is.na(l_macd) && !is.na(l_sig)) biases <- c(biases, if (l_macd > l_sig) 1 else -1)

  score <- if (length(biases) > 0) round(mean(biases) * 100) else 0
  verdict <- if (score >= 55) "Strong Buy" else if (score >= 20) "Buy" else if (score <= -55) "Strong Sell" else if (score <= -20) "Sell" else "Neutral"

  prev <- df[max(1, n - 1), ]
  pivot <- (prev$high + prev$low + prev$close) / 3
  r1 <- 2 * pivot - prev$low
  s1 <- 2 * pivot - prev$high

  df$sma20 <- sma20
  df$sma50 <- sma50
  df$bb_up <- bb_mat[, "up"]
  df$bb_dn <- bb_mat[, "dn"]
  df$rsi14 <- rsi14
  df$macd <- macd_mat[, "macd"]
  df$macd_signal <- macd_mat[, "signal"]
  df$macd_hist <- macd_mat[, "macd"] - macd_mat[, "signal"]

  list(
    series = df,
    score = score,
    verdict = verdict,
    latest_rsi = l_rsi,
    latest_macd_hist = l_macd - l_sig,
    latest_sma20 = l_sma20,
    latest_sma50 = l_sma50,
    latest_atr = l_atr,
    ann_vol = ann_vol,
    pivot = pivot,
    s1 = s1,
    r1 = r1
  )
}

compute_fundamental_score <- function(quote_obj, fund) {
  if (is.null(fund)) {
    return(list(score = 50, grade = "N/A", label = "Price-Action Only", pillars = data.frame()))
  }
  val_s <- 55
  if (!is.na(fund$trailing_pe)) val_s <- val_s + if (fund$trailing_pe >= 5 && fund$trailing_pe <= 16) 22 else if (fund$trailing_pe > 40 || fund$trailing_pe < 0) -18 else 6
  if (!is.na(fund$price_to_book)) val_s <- val_s + if (fund$price_to_book <= 2.2) 12 else if (fund$price_to_book > 6) -10 else 3
  val_s <- min(100, max(10, val_s))

  prof_s <- 50
  if (!is.na(fund$roe)) prof_s <- prof_s + if (fund$roe >= 0.18) 25 else if (fund$roe >= 0.10) 12 else if (fund$roe < 0) -20 else 0
  if (!is.na(fund$operating_margin)) prof_s <- prof_s + if (fund$operating_margin >= 0.20) 15 else if (fund$operating_margin < 0) -15 else 5
  prof_s <- min(100, max(10, prof_s))

  health_s <- 55
  if (!is.na(fund$current_ratio)) health_s <- health_s + if (fund$current_ratio >= 1.4) 18 else if (fund$current_ratio < 0.9) -15 else 6
  if (!is.na(fund$free_cash_flow)) health_s <- health_s + if (fund$free_cash_flow > 0) 12 else -15
  health_s <- min(100, max(10, health_s))

  growth_s <- 50
  if (!is.na(fund$revenue_growth)) growth_s <- growth_s + if (fund$revenue_growth >= 0.12) 20 else if (fund$revenue_growth < -0.05) -15 else 6
  if (!is.na(fund$dividend_yield)) growth_s <- growth_s + if (fund$dividend_yield >= 0.04) 18 else if (fund$dividend_yield >= 0.02) 10 else 0
  growth_s <- min(100, max(10, growth_s))

  overall <- round(0.28 * val_s + 0.28 * prof_s + 0.22 * health_s + 0.22 * growth_s)
  grade <- if (overall >= 85) "A+" else if (overall >= 75) "A" else if (overall >= 65) "B+" else if (overall >= 55) "B" else if (overall >= 45) "C" else "D"
  upside <- if (!is.na(fund$target_mean) && quote_obj$price > 0) ((fund$target_mean - quote_obj$price) / quote_obj$price) * 100 else NA_real_

  list(
    score = overall,
    grade = grade,
    upside_pct = upside,
    pillars = data.frame(
      Pillar = c("Valuation", "Profitability", "Balance Sheet", "Growth & Yield"),
      Score = c(val_s, prof_s, health_s, growth_s),
      stringsAsFactors = FALSE
    )
  )
}

compute_forecast <- function(quote_obj, horizon_days = 30) {
  closes <- tail(quote_obj$candles$close, 120)
  n <- length(closes)
  if (n < 15) return(NULL)

  log_p <- log(closes)
  ts_log <- stats::ts(log_p)
  holt_fit <- forecast::holt(ts_log, h = horizon_days, damped = TRUE)
  holt_mean <- as.numeric(holt_fit$mean)

  idx <- seq_len(n)
  ols <- stats::lm(log_p ~ idx)
  r2 <- summary(ols)$r.squared
  slope <- stats::coef(ols)[2] %||% 0
  ols_proj <- tail(log_p, 1) + slope * seq_len(horizon_days)

  w_ols <- min(0.75, max(0.35, 0.35 + 0.40 * r2))
  blended_log <- (1 - w_ols) * holt_mean + w_ols * ols_proj

  daily_sigma <- min(0.08, max(0.004, stats::sd(diff(log_p))))
  steps <- seq_len(horizon_days)
  sigma_t <- daily_sigma * sqrt(steps)

  base_p <- exp(blended_log)
  last_date <- tail(quote_obj$candles$date, 1)
  future_dates <- last_date + as.difftime(steps, units = "days")

  # Rolling 5-day backtest on tail
  mape <- NA_real_
  dir_acc <- NA_real_
  if (n >= 35) {
    errs <- c()
    hits <- c()
    for (i in seq(max(20, n - 20), n - 5)) {
      sub_fit <- forecast::holt(stats::ts(log_p[1:i]), h = 5, damped = TRUE)
      pred <- exp(as.numeric(sub_fit$mean[5]))
      act <- closes[i + 5]
      errs <- c(errs, abs(act - pred) / act * 100)
      hits <- c(hits, (pred >= closes[i]) == (act >= closes[i]))
    }
    mape <- mean(errs)
    dir_acc <- mean(hits) * 100
  }

  list(
    history = tail(quote_obj$candles[, c("date", "close")], 45),
    future = data.frame(
      date = future_dates,
      base = base_p,
      upper1 = base_p * exp(sigma_t),
      lower1 = base_p * exp(-sigma_t),
      upper2 = base_p * exp(1.96 * sigma_t),
      lower2 = base_p * exp(-1.96 * sigma_t)
    ),
    current_price = quote_obj$price,
    projected_price = tail(base_p, 1),
    projected_pct = ((tail(base_p, 1) - quote_obj$price) / quote_obj$price) * 100,
    bull_95 = tail(base_p * exp(1.96 * sigma_t), 1),
    bear_95 = tail(base_p * exp(-1.96 * sigma_t), 1),
    r2 = r2,
    daily_vol_pct = daily_sigma * 100,
    mape = mape,
    dir_acc = dir_acc
  )
}

# ------------------------------------------------------------------------------
# UI Definition (bslib Midnight Canadian Financial Terminal Theme)
# ------------------------------------------------------------------------------

terminal_theme <- bs_theme(
  version = 5,
  bg = "#0B1622",
  fg = "#F1F5F9",
  primary = "#10B981",
  secondary = "#38BDF8",
  warning = "#F59E0B",
  danger = "#F43F5E",
  base_font = font_google("Plus Jakarta Sans"),
  heading_font = font_google("Space Grotesk"),
  code_font = font_google("JetBrains Mono")
)

ui <- page_navbar(
  title = tags$span(
    tags$strong("MaplePulse", style = "color:#F1F5F9;"),
    tags$span(" TSX/TSXV", class = "badge bg-success ms-2", style = "font-size:0.7rem;")
  ),
  theme = terminal_theme,
  fillable = FALSE,
  header = tags$head(
    tags$style(HTML("
      .card { background-color: #102030 !important; border: 1px solid #233B54 !important; border-radius: 14px !important; }
      .metric-box { background: #162A3E; border: 1px solid #233B54; border-radius: 12px; padding: 12px; }
      .mono-num { font-family: 'JetBrains Mono', monospace; font-variant-numeric: tabular-nums; }
      .bull-txt { color: #10B981; font-weight: 600; }
      .bear-txt { color: #F43F5E; font-weight: 600; }
      .warn-txt { color: #F59E0B; font-weight: 600; }
      .cyan-txt { color: #38BDF8; font-weight: 600; }
      .dataTables_wrapper { color: #F1F5F9 !important; }
      table.dataTable tbody tr { background-color: #102030 !important; color: #F1F5F9 !important; }
    "))
  ),

  # --- TAB 1: Markets & Live Analyzer ---
  nav_panel(
    "Markets & Analyzer",
    layout_sidebar(
      sidebar = sidebar(
        width = 310,
        bg = "#102030",
        selectInput(
          "selected_symbol",
          "Active Canadian Equity",
          choices = stats::setNames(TSX_UNIVERSE$symbol, paste0(TSX_UNIVERSE$symbol, " — ", TSX_UNIVERSE$name)),
          selected = "RY.TO"
        ),
        textInput("custom_search", "Search Any TSX / TSXV Ticker", placeholder = "e.g. L.TO, HIVE.V, BCE.TO"),
        actionButton("search_btn", "Search Exchange", class = "btn-outline-info btn-sm w-100 mb-2"),
        uiOutput("search_hits_ui"),
        hr(),
        selectInput(
          "chart_range",
          "Chart Lookback",
          choices = c("5 Days" = "5d", "1 Month" = "1mo", "3 Months" = "3mo", "6 Months" = "6mo", "1 Year" = "1y", "5 Years" = "5y"),
          selected = "1y"
        ),
        checkboxInput("show_candles", "Candlestick Chart", value = TRUE),
        checkboxInput("show_sma", "SMA 20 / SMA 50 Overlays", value = TRUE),
        checkboxInput("show_bb", "Bollinger Bands (20, 2σ)", value = FALSE),
        hr(),
        actionButton("add_watchlist_btn", "Add / Toggle Watchlist", class = "btn-primary w-100 mb-2"),
        actionButton("refresh_symbol_btn", "Refresh Live Quote", class = "btn-outline-light btn-sm w-100")
      ),

      # Main Analytics Canvas
      uiOutput("macro_banner_ui"),
      uiOutput("stock_header_ui"),
      navset_card_tab(
        nav_panel(
          "Technical Analysis",
          br(),
          plotlyOutput("tech_price_chart", height = "480px"),
          br(),
          uiOutput("tech_metrics_ui")
        ),
        nav_panel(
          "Fundamental Analysis",
          br(),
          uiOutput("fundamental_ui")
        ),
        nav_panel(
          "Quantitative Forecast",
          br(),
          radioButtons(
            "forecast_horizon",
            "Forecast Horizon",
            choices = c("7 Trading Days" = "7", "30 Trading Days" = "30", "90 Trading Days" = "90"),
            selected = "30",
            inline = TRUE
          ),
          plotlyOutput("forecast_chart", height = "380px"),
          br(),
          uiOutput("forecast_metrics_ui")
        )
      )
    )
  ),

  # --- TAB 2: Canadian Technical Screener ---
  nav_panel(
    "TSX Screener",
    card(
      card_header("Multi-Factor TSX / TSXV Technical Scanner"),
      card_body(
        fluidRow(
          column(4, selectInput("screener_sector", "Sector", choices = c("All", unique(TSX_UNIVERSE$sector)))),
          column(4, selectInput("screener_filter", "Signal Filter", choices = c("All", "Bullish (Buy / Strong Buy)", "Oversold (RSI <= 35)", "Overbought (RSI >= 70)"))),
          column(4, br(), actionButton("run_screener_btn", "Scan Live TSX Universe", class = "btn-primary w-100"))
        ),
        br(),
        DTOutput("screener_table")
      )
    )
  ),

  # --- TAB 3: Watchlist ---
  nav_panel(
    "Watchlist",
    card(
      card_header("Tracked Canadian Equities"),
      card_body(
        DTOutput("watchlist_table"),
        br(),
        actionButton("refresh_watchlist_btn", "Refresh Watchlist Quotes", class = "btn-outline-info btn-sm")
      )
    )
  ),

  # --- TAB 4: Real-Time Alerts ---
  nav_panel(
    "Real-Time Alerts",
    layout_columns(
      col_widths = c(5, 7),
      card(
        card_header("Arm Price or Technical Alert"),
        card_body(
          selectInput("alert_symbol", "Ticker", choices = TSX_UNIVERSE$symbol, selected = "RY.TO"),
          selectInput(
            "alert_condition",
            "Condition",
            choices = c(
              "Price Crosses Above (CAD)" = "ABOVE",
              "Price Drops Below (CAD)" = "BELOW",
              "Intraday Gain >= (%)" = "PCT_GAIN",
              "Intraday Drop <= (%)" = "PCT_DROP",
              "RSI(14) Overbought >=" = "RSI_ABOVE",
              "RSI(14) Oversold <=" = "RSI_BELOW"
            )
          ),
          numericInput("alert_target", "Threshold Value", value = 160, min = 0.01, step = 0.5),
          actionButton("create_alert_btn", "Arm Real-Time Alert", class = "btn-warning w-100 mb-3"),
          checkboxInput("live_polling_on", "Enable 20s Real-Time Polling", value = TRUE),
          actionButton("scan_alerts_now_btn", "Scan Active Alerts Now", class = "btn-outline-info w-100")
        )
      ),
      card(
        card_header("Active Alert Rules & Triggered History"),
        card_body(
          h6("Armed Rules"),
          DTOutput("alerts_table"),
          hr(),
          h6("Triggered Alert Log"),
          DTOutput("alert_events_table")
        )
      )
    )
  )
)

# ------------------------------------------------------------------------------
# Server Logic
# ------------------------------------------------------------------------------

server <- function(input, output, session) {
  rv <- reactiveValues(
    watchlist = TSX_UNIVERSE[1:3, ],
    alerts = data.frame(
      id = character(0),
      symbol = character(0),
      condition = character(0),
      target = numeric(0),
      active = logical(0),
      stringsAsFactors = FALSE
    ),
    alert_events = data.frame(
      time = character(0),
      symbol = character(0),
      message = character(0),
      stringsAsFactors = FALSE
    ),
    search_hits = data.frame(),
    screener_df = data.frame(),
    macro = list(cad_usd = NA_real_, boc_rate = NA_real_),
    tsx_index = NULL
  )

  # Initial Macro + S&P/TSX Composite load
  observe({
    isolate({
      rv$macro <- fetch_boc_macro()
      rv$tsx_index <- fetch_chart_quote("^GSPTSE", "3mo", "1d")
    })
  })

  # Active symbol quote + fundamentals reactive
  active_data <- reactive({
    input$refresh_symbol_btn
    sym <- input$selected_symbol
    rng <- input$chart_range
    interval <- if (rng == "5d") "15m" else if (rng == "5y") "1wk" else "1d"

    withProgress(message = paste("Fetching live TSX feed for", sym, "..."), value = 0.4, {
      quote_obj <- fetch_chart_quote(sym, rng, interval)
      if (is.null(quote_obj)) return(NULL)
      tech <- compute_technicals(quote_obj)
      fund <- fetch_fundamentals(sym)
      fund_eval <- compute_fundamental_score(quote_obj, fund)
      list(quote = quote_obj, tech = tech, fund = fund, fund_eval = fund_eval)
    })
  })

  # Search handler
  observeEvent(input$search_btn, {
    req(nzchar(trimws(input$custom_search)))
    hits <- search_canadian_tickers(input$custom_search)
    rv$search_hits <- hits
    if (nrow(hits) > 0) {
      choices_map <- stats::setNames(
        c(TSX_UNIVERSE$symbol, hits$symbol),
        c(paste0(TSX_UNIVERSE$symbol, " — ", TSX_UNIVERSE$name), paste0(hits$symbol, " — ", hits$name))
      )
      choices_map <- choices_map[!duplicated(choices_map)]
      updateSelectInput(session, "selected_symbol", choices = choices_map, selected = hits$symbol[1])
      updateSelectInput(session, "alert_symbol", choices = unname(choices_map), selected = hits$symbol[1])
    } else {
      showNotification("No TSX/TSXV symbols matched your search.", type = "warning")
    }
  })

  output$search_hits_ui <- renderUI({
    hits <- rv$search_hits
    if (nrow(hits) == 0) return(NULL)
    tags$div(
      class = "small text-info mb-2",
      paste("Found", nrow(hits), "Canadian tickers; loaded", hits$symbol[1])
    )
  })

  # Macro + S&P/TSX Composite Banner
  output$macro_banner_ui <- renderUI({
    tsx <- rv$tsx_index
    m <- rv$macro
    div(
      class = "metric-box mb-3 d-flex flex-wrap justify-content-between align-items-center",
      div(
        tags$span("S&P/TSX COMPOSITE ", class = "warn-txt small"),
        if (!is.null(tsx)) {
          cls <- if (tsx$change >= 0) "bull-txt mono-num ms-2" else "bear-txt mono-num ms-2"
          tags$span(sprintf("%,.2f (%+.2f%%)", tsx$price, tsx$change_pct), class = cls)
        } else {
          tags$span("Loading...", class = "text-muted ms-2")
        }
      ),
      div(
        class = "mono-num small",
        if (!is.na(m$boc_rate)) tags$span(sprintf("BoC Rate: %.2f%%  |  ", m$boc_rate)),
        if (!is.na(m$cad_usd)) tags$span(sprintf("CAD/USD: %.4f", m$cad_usd), class = "cyan-txt")
      )
    )
  })

  # Stock Header Card
  output$stock_header_ui <- renderUI({
    dat <- active_data()
    if (is.null(dat)) {
      return(div(class = "alert alert-danger", "Unable to fetch quote from Yahoo Finance. Please retry."))
    }
    q <- dat$quote
    t <- dat$tech
    cls <- if (q$change >= 0) "bull-txt" else "bear-txt"
    div(
      class = "metric-box mb-3 d-flex flex-wrap justify-content-between align-items-center",
      div(
        tags$h3(sprintf("%s (%s)", q$symbol, q$exchange), class = "mb-0"),
        tags$div(q$name, class = "text-secondary small")
      ),
      div(
        class = "text-end",
        tags$h3(sprintf("%s $%.2f", q$currency, q$price), class = "mono-num mb-0"),
        tags$div(sprintf("%+.2f (%+.2f%%)  •  Technical: %s (%d)", q$change, q$change_pct, t$verdict, t$score), class = paste(cls, "mono-num small"))
      )
    )
  })

  # Technical Chart (Candles/Line + SMA + BB + RSI + MACD)
  output$tech_price_chart <- renderPlotly({
    dat <- active_data()
    req(dat)
    df <- dat$tech$series

    p1 <- plot_ly(df, x = ~date)
    if (isTRUE(input$show_candles)) {
      p1 <- p1 %>% add_trace(
        type = "candlestick", open = ~open, high = ~high, low = ~low, close = ~close,
        name = dat$quote$symbol,
        increasing = list(line = list(color = "#10B981")),
        decreasing = list(line = list(color = "#F43F5E"))
      )
    } else {
      p1 <- p1 %>% add_lines(y = ~close, name = "Close", line = list(color = "#10B981", width = 2))
    }

    if (isTRUE(input$show_sma)) {
      p1 <- p1 %>%
        add_lines(y = ~sma20, name = "SMA 20", line = list(color = "#F59E0B", width = 1.4)) %>%
        add_lines(y = ~sma50, name = "SMA 50", line = list(color = "#A78BFA", width = 1.4))
    }
    if (isTRUE(input$show_bb)) {
      p1 <- p1 %>%
        add_lines(y = ~bb_up, name = "BB Upper", line = list(color = "#38BDF8", dash = "dot", width = 1)) %>%
        add_lines(y = ~bb_dn, name = "BB Lower", line = list(color = "#38BDF8", dash = "dot", width = 1), fill = "tonexty", fillcolor = "rgba(56,189,248,0.08)")
    }
    p1 <- p1 %>% layout(yaxis = list(title = "Price (CAD)"), xaxis = list(rangeslider = list(visible = FALSE)))

    p2 <- plot_ly(df, x = ~date, y = ~rsi14, type = "scatter", mode = "lines", name = "RSI(14)", line = list(color = "#F59E0B")) %>%
      layout(yaxis = list(title = "RSI", range = c(0, 100)))

    p3 <- plot_ly(df, x = ~date) %>%
      add_bars(y = ~macd_hist, name = "MACD Hist", marker = list(color = ifelse(df$macd_hist >= 0, "#10B981", "#F43F5E"))) %>%
      add_lines(y = ~macd, name = "MACD", line = list(color = "#38BDF8")) %>%
      add_lines(y = ~macd_signal, name = "Signal", line = list(color = "#F59E0B")) %>%
      layout(yaxis = list(title = "MACD"))

    subplot(p1, p2, p3, nrows = 3, shareX = TRUE, heights = c(0.58, 0.21, 0.21), titleY = TRUE) %>%
      layout(
        paper_bgcolor = "#102030",
        plot_bgcolor = "#0B1622",
        font = list(color = "#F1F5F9"),
        legend = list(orientation = "h", y = 1.04)
      )
  })

  output$tech_metrics_ui <- renderUI({
    dat <- active_data()
    req(dat)
    t <- dat$tech
    layout_columns(
      col_widths = c(3, 3, 3, 3),
      div(class = "metric-box", tags$div("RSI (14)", class = "small text-secondary"), tags$h5(sprintf("%.1f", t$latest_rsi), class = "mono-num")),
      div(class = "metric-box", tags$div("Pivot / S1 / R1", class = "small text-secondary"), tags$h5(sprintf("$%.2f / $%.2f / $%.2f", t$pivot, t$s1, t$r1), class = "mono-num")),
      div(class = "metric-box", tags$div("ATR (14)", class = "small text-secondary"), tags$h5(sprintf("$%.2f", t$latest_atr), class = "mono-num")),
      div(class = "metric-box", tags$div("Ann. Volatility", class = "small text-secondary"), tags$h5(sprintf("%.1f%%", t$ann_vol), class = "mono-num"))
    )
  })

  output$fundamental_ui <- renderUI({
    dat <- active_data()
    req(dat)
    f <- dat$fund
    ev <- dat$fund_eval
    if (is.null(f)) {
      return(div(class = "alert alert-secondary", "Detailed corporate fundamentals unavailable for this ticker (e.g., index or ETF)."))
    }
    fmt <- function(x, suffix = "") if (is.na(x)) "--" else sprintf("%.2f%s", x, suffix)
    tagList(
      div(
        class = "metric-box mb-3 d-flex justify-content-between align-items-center",
        div(
          tags$h5(sprintf("Fundamental Health Grade: %s (%d/100)", ev$grade, ev$score)),
          tags$div(sprintf("Sector: %s  •  Industry: %s", f$sector %||% "--", f$industry %||% "--"), class = "small text-secondary")
        ),
        if (!is.na(ev$upside_pct)) {
          tags$span(sprintf("Analyst Target Upside: %+.1f%%", ev$upside_pct), class = if (ev$upside_pct >= 0) "bull-txt mono-num" else "bear-txt mono-num")
        }
      ),
      layout_columns(
        col_widths = c(3, 3, 3, 3),
        div(class = "metric-box", tags$div("Trailing P/E", class = "small text-secondary"), tags$h5(fmt(f$trailing_pe), class = "mono-num")),
        div(class = "metric-box", tags$div("Forward P/E", class = "small text-secondary"), tags$h5(fmt(f$forward_pe), class = "mono-num")),
        div(class = "metric-box", tags$div("Dividend Yield", class = "small text-secondary"), tags$h5(fmt(f$dividend_yield * 100, "%"), class = "mono-num")),
        div(class = "metric-box", tags$div("ROE", class = "small text-secondary"), tags$h5(fmt(f$roe * 100, "%"), class = "mono-num"))
      ),
      br(),
      layout_columns(
        col_widths = c(3, 3, 3, 3),
        div(class = "metric-box", tags$div("Price / Book", class = "small text-secondary"), tags$h5(fmt(f$price_to_book), class = "mono-num")),
        div(class = "metric-box", tags$div("Profit Margin", class = "small text-secondary"), tags$h5(fmt(f$profit_margin * 100, "%"), class = "mono-num")),
        div(class = "metric-box", tags$div("Debt / Equity", class = "small text-secondary"), tags$h5(fmt(f$debt_to_equity, "%"), class = "mono-num")),
        div(class = "metric-box", tags$div("Analyst Target", class = "small text-secondary"), tags$h5(fmt(f$target_mean), class = "mono-num"))
      ),
      br(),
      if (!is.na(f$summary)) div(class = "metric-box small text-secondary", f$summary)
    )
  })

  # Quantitative Forecast
  forecast_res <- reactive({
    dat <- active_data()
    req(dat)
    h <- as.integer(input$forecast_horizon)
    compute_forecast(dat$quote, h)
  })

  output$forecast_chart <- renderPlotly({
    fc <- forecast_res()
    req(fc)
    plot_ly() %>%
      add_lines(data = fc$history, x = ~date, y = ~close, name = "Historical Close", line = list(color = "#10B981", width = 2.2)) %>%
      add_ribbons(data = fc$future, x = ~date, ymin = ~lower2, ymax = ~upper2, name = "95% Confidence Cone", fillcolor = "rgba(56,189,248,0.14)", line = list(color = "transparent")) %>%
      add_ribbons(data = fc$future, x = ~date, ymin = ~lower1, ymax = ~upper1, name = "68% Confidence Cone", fillcolor = "rgba(56,189,248,0.26)", line = list(color = "transparent")) %>%
      add_lines(data = fc$future, x = ~date, y = ~base, name = "Ensemble Base Projection", line = list(color = "#38BDF8", dash = "dash", width = 2.4)) %>%
      layout(
        paper_bgcolor = "#102030",
        plot_bgcolor = "#0B1622",
        font = list(color = "#F1F5F9"),
        yaxis = list(title = "Price (CAD)"),
        xaxis = list(title = "Date"),
        legend = list(orientation = "h", y = 1.06)
      )
  })

  output$forecast_metrics_ui <- renderUI({
    fc <- forecast_res()
    req(fc)
    layout_columns(
      col_widths = c(3, 3, 3, 3),
      div(class = "metric-box", tags$div("Bear Target (95%)", class = "small text-secondary"), tags$h5(sprintf("$%.2f", fc$bear_95), class = "bear-txt mono-num")),
      div(class = "metric-box", tags$div("Base Projection", class = "small text-secondary"), tags$h5(sprintf("$%.2f (%+.2f%%)", fc$projected_price, fc$projected_pct), class = "cyan-txt mono-num")),
      div(class = "metric-box", tags$div("Bull Target (95%)", class = "small text-secondary"), tags$h5(sprintf("$%.2f", fc$bull_95), class = "bull-txt mono-num")),
      div(class = "metric-box", tags$div("Backtest MAPE / Acc", class = "small text-secondary"), tags$h5(sprintf("%.1f%% / %.0f%%", fc$mape, fc$dir_acc), class = "mono-num"))
    )
  })

  # Screener
  observeEvent(input$run_screener_btn, {
    universe <- TSX_UNIVERSE
    if (input$screener_sector != "All") {
      universe <- universe[universe$sector == input$screener_sector, ]
    }
    withProgress(message = "Scanning live TSX universe...", value = 0, {
      rows <- lapply(seq_len(nrow(universe)), function(i) {
        incProgress(1 / nrow(universe))
        sym <- universe$symbol[i]
        q <- fetch_chart_quote(sym, "3mo", "1d")
        if (is.null(q)) return(NULL)
        t <- compute_technicals(q)
        data.frame(
          Symbol = sym,
          Name = universe$name[i],
          Sector = universe$sector[i],
          Price_CAD = round(q$price, 2),
          Change_Pct = round(q$change_pct, 2),
          RSI_14 = round(t$latest_rsi, 1),
          Score = t$score,
          Signal = t$verdict,
          Ann_Vol_Pct = round(t$ann_vol, 1),
          stringsAsFactors = FALSE
        )
      })
      df <- do.call(rbind, Filter(Negate(is.null), rows))
      if (!is.null(df) && nrow(df) > 0) {
        if (input$screener_filter == "Bullish (Buy / Strong Buy)") df <- df[df$Signal %in% c("Buy", "Strong Buy"), ]
        if (input$screener_filter == "Oversold (RSI <= 35)") df <- df[!is.na(df$RSI_14) & df$RSI_14 <= 35, ]
        if (input$screener_filter == "Overbought (RSI >= 70)") df <- df[!is.na(df$RSI_14) & df$RSI_14 >= 70, ]
        df <- df[order(-df$Score), ]
      }
      rv$screener_df <- df %||% data.frame()
    })
  })

  output$screener_table <- renderDT({
    req(nrow(rv$screener_df) > 0)
    datatable(rv$screener_df, options = list(pageLength = 15, scrollX = TRUE), rownames = FALSE)
  })

  # Watchlist
  observeEvent(input$add_watchlist_btn, {
    sym <- input$selected_symbol
    if (sym %in% rv$watchlist$symbol) {
      rv$watchlist <- rv$watchlist[rv$watchlist$symbol != sym, ]
      showNotification(paste("Removed", sym, "from watchlist"), type = "message")
    } else {
      dat <- active_data()
      nm <- if (!is.null(dat)) dat$quote$name else sym
      ex <- if (!is.null(dat)) dat$quote$exchange else "TSX"
      rv$watchlist <- rbind(rv$watchlist, data.frame(symbol = sym, name = nm, exchange = ex, sector = "TSX", stringsAsFactors = FALSE))
      showNotification(paste("Added", sym, "to watchlist"), type = "message")
    }
  })

  watchlist_live <- reactive({
    input$refresh_watchlist_btn
    wl <- rv$watchlist
    if (nrow(wl) == 0) return(data.frame())
    rows <- lapply(seq_len(nrow(wl)), function(i) {
      q <- fetch_chart_quote(wl$symbol[i], "3mo", "1d")
      if (is.null(q)) return(data.frame(Symbol = wl$symbol[i], Name = wl$name[i], Price_CAD = NA, Change_Pct = NA, RSI_14 = NA, Verdict = "--"))
      t <- compute_technicals(q)
      data.frame(
        Symbol = q$symbol,
        Name = q$name,
        Price_CAD = round(q$price, 2),
        Change_Pct = round(q$change_pct, 2),
        RSI_14 = round(t$latest_rsi, 1),
        Verdict = t$verdict,
        stringsAsFactors = FALSE
      )
    })
    do.call(rbind, rows)
  })

  output$watchlist_table <- renderDT({
    datatable(watchlist_live(), options = list(pageLength = 12, scrollX = TRUE), rownames = FALSE)
  })

  # Real-Time Alerts
  observeEvent(input$create_alert_btn, {
    req(!is.na(input$alert_target), input$alert_target > 0)
    new_id <- paste0("ALT-", format(Sys.time(), "%H%M%S"), "-", sample(100:999, 1))
    rv$alerts <- rbind(rv$alerts, data.frame(
      id = new_id,
      symbol = input$alert_symbol,
      condition = input$alert_condition,
      target = as.numeric(input$alert_target),
      active = TRUE,
      stringsAsFactors = FALSE
    ))
    showNotification(paste("Armed alert for", input$alert_symbol), type = "message")
  })

  evaluate_alerts <- function() {
    al <- rv$alerts
    if (nrow(al) == 0 || !any(al$active)) return()
    for (i in which(al$active)) {
      sym <- al$symbol[i]
      q <- fetch_chart_quote(sym, "3mo", "1d")
      if (is.null(q)) next
      t <- compute_technicals(q)
      cond <- al$condition[i]
      tgt <- al$target[i]
      hit <- switch(
        cond,
        "ABOVE" = q$price >= tgt,
        "BELOW" = q$price <= tgt,
        "PCT_GAIN" = q$change_pct >= tgt,
        "PCT_DROP" = q$change_pct <= -abs(tgt),
        "RSI_ABOVE" = !is.na(t$latest_rsi) && t$latest_rsi >= tgt,
        "RSI_BELOW" = !is.na(t$latest_rsi) && t$latest_rsi <= tgt,
        FALSE
      )
      if (isTRUE(hit)) {
        msg <- sprintf("%s triggered %s %.2f (Live CAD %.2f, RSI %.1f)", sym, cond, tgt, q$price, t$latest_rsi)
        rv$alerts$active[i] <- FALSE
        rv$alert_events <- rbind(
          data.frame(time = format(Sys.time(), "%Y-%m-%d %H:%M:%S"), symbol = sym, message = msg, stringsAsFactors = FALSE),
          rv$alert_events
        )
        showNotification(msg, type = "warning", duration = 10)
      }
    }
  }

  observeEvent(input$scan_alerts_now_btn, {
    evaluate_alerts()
  })

  poll_timer <- reactiveTimer(20000)
  observe({
    poll_timer()
    if (isTRUE(input$live_polling_on)) {
      isolate(evaluate_alerts())
    }
  })

  output$alerts_table <- renderDT({
    datatable(rv$alerts, options = list(pageLength = 6, scrollX = TRUE), rownames = FALSE)
  })

  output$alert_events_table <- renderDT({
    datatable(rv$alert_events, options = list(pageLength = 8, scrollX = TRUE), rownames = FALSE)
  })
}

shinyApp(ui = ui, server = server)
