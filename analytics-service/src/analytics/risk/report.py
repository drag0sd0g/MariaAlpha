"""Self-contained HTML risk report.

Mirrors the pattern of the backtester's ``HtmlReportWriter`` in strategy-engine: one string, no
CDN, no external stylesheet, no JavaScript. It has to open from a ``curl -o`` on a laptop with no
network, which rules out every chart library — the correlation heat map and the bars are inline
SVG and table cells.
"""

from __future__ import annotations

import html
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from analytics.risk.engine import RiskReport

_CSS = """
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body { margin: 0; padding: 2rem; font: 14px/1.5 ui-sans-serif, system-ui, -apple-system, sans-serif;
       background: #f8fafc; color: #0f172a; }
h1 { font-size: 1.5rem; margin: 0 0 .25rem; }
h2 { font-size: 1.05rem; margin: 2rem 0 .75rem; padding-bottom: .35rem;
     border-bottom: 1px solid #e2e8f0; }
.sub { color: #64748b; margin: 0 0 1.5rem; font-size: .85rem; }
.tiles { display: flex; flex-wrap: wrap; gap: .75rem; }
.tile { background: #fff; border: 1px solid #e2e8f0; border-radius: .5rem; padding: .85rem 1.1rem;
        min-width: 11rem; flex: 1 1 11rem; }
.tile .label { font-size: .7rem; text-transform: uppercase; letter-spacing: .04em; color: #64748b; }
.tile .value { font-size: 1.35rem; font-weight: 600; margin-top: .2rem;
               font-variant-numeric: tabular-nums; }
.tile .note { font-size: .72rem; color: #94a3b8; margin-top: .2rem; }
table { border-collapse: collapse; width: 100%; background: #fff; border: 1px solid #e2e8f0;
        border-radius: .5rem; overflow: hidden; }
th, td { padding: .45rem .7rem; text-align: right; border-bottom: 1px solid #f1f5f9;
         font-variant-numeric: tabular-nums; }
th { background: #f1f5f9; font-size: .72rem; text-transform: uppercase; letter-spacing: .04em;
     color: #475569; }
th:first-child, td:first-child { text-align: left; }
tr:last-child td { border-bottom: none; }
.pos { color: #15803d; } .neg { color: #b91c1c; } .muted { color: #94a3b8; }
.callout { background: #ecfdf5; border: 1px solid #a7f3d0; border-radius: .5rem;
           padding: .85rem 1.1rem; margin: 1rem 0; }
.callout.warn { background: #fffbeb; border-color: #fde68a; }
.wrap { overflow-x: auto; }
.badge { display: inline-block; padding: .1rem .45rem; border-radius: .25rem; font-size: .7rem;
         font-weight: 600; }
.badge.ok { background: #dcfce7; color: #166534; }
.badge.bad { background: #fee2e2; color: #991b1b; }
@media (prefers-color-scheme: dark) {
  body { background: #0b1120; color: #e2e8f0; }
  .tile, table { background: #111c33; border-color: #1e293b; }
  th { background: #16233d; color: #94a3b8; }
  th, td { border-bottom-color: #1a2740; }
  h2 { border-bottom-color: #1e293b; }
  .callout { background: #052e21; border-color: #14532d; }
  .callout.warn { background: #2e2408; border-color: #78350f; }
}
"""


def _usd(value: float) -> str:
    sign = "-" if value < 0 else ""
    return f"{sign}${abs(value):,.2f}"


def _cls(value: float) -> str:
    return "pos" if value > 0 else "neg" if value < 0 else "muted"


def _heatmap(symbols: tuple[str, ...], correlation: list[list[float]]) -> str:
    """Inline-SVG correlation heat map. Blue for positive, red for negative."""
    if not symbols:
        return "<p class='muted'>No correlation matrix available.</p>"
    n = len(symbols)
    cell, pad = 46, 70
    width, height = pad + n * cell + 10, pad + n * cell + 10
    parts = [
        f"<svg width='{width}' height='{height}' viewBox='0 0 {width} {height}' "
        f"role='img' aria-label='Correlation matrix'>"
    ]
    for j, symbol in enumerate(symbols):
        x = pad + j * cell + cell / 2
        parts.append(
            f"<text x='{x}' y='{pad - 8}' font-size='10' text-anchor='middle' "
            f"fill='#64748b'>{html.escape(symbol)}</text>"
        )
        y = pad + j * cell + cell / 2 + 3
        parts.append(
            f"<text x='{pad - 8}' y='{y}' font-size='10' text-anchor='end' "
            f"fill='#64748b'>{html.escape(symbol)}</text>"
        )
    for i in range(n):
        for j in range(n):
            rho = float(correlation[i][j])
            intensity = min(abs(rho), 1.0)
            if rho >= 0:
                colour = f"rgba(37, 99, 235, {0.08 + 0.82 * intensity:.3f})"
            else:
                colour = f"rgba(220, 38, 38, {0.08 + 0.82 * intensity:.3f})"
            x, y = pad + j * cell, pad + i * cell
            parts.append(
                f"<rect x='{x}' y='{y}' width='{cell - 2}' height='{cell - 2}' rx='3' "
                f"fill='{colour}'/>"
            )
            text_fill = "#fff" if intensity > 0.55 else "#0f172a"
            parts.append(
                f"<text x='{x + cell / 2 - 1}' y='{y + cell / 2 + 3}' font-size='10' "
                f"text-anchor='middle' fill='{text_fill}'>{rho:.2f}</text>"
            )
    parts.append("</svg>")
    return "".join(parts)


def render(report: RiskReport, correlation: list[list[float]] | None = None) -> str:
    """Render ``report`` as a standalone HTML document."""
    p = report.parametric
    tiles = [
        ("Parametric VaR", _usd(p.var_usd), f"{report.confidence:.0%} / {report.horizon_days:g}d"),
        ("Expected shortfall", _usd(p.expected_shortfall_usd), "Gaussian, parametric"),
    ]
    if report.historical is not None:
        h = report.historical
        note = f"{h.observations} obs" + ("" if h.sufficient else " — insufficient")
        tiles.append(("Historical VaR", _usd(h.var_usd), note))
    if report.monte_carlo is not None:
        m = report.monte_carlo
        tiles.append(("Monte-Carlo VaR", _usd(m.var_usd), f"{m.simulations:,} paths"))
    tiles.extend(
        [
            ("Gross exposure", _usd(report.gross_exposure_usd), "sum |notional|"),
            ("Net exposure", _usd(report.net_exposure_usd), "sum notional"),
        ]
    )

    tile_html = "".join(
        f"<div class='tile'><div class='label'>{html.escape(label)}</div>"
        f"<div class='value'>{html.escape(value)}</div>"
        f"<div class='note'>{html.escape(note)}</div></div>"
        for label, value, note in tiles
    )

    ratio = report.diversification_ratio
    ratio_text = "unbounded (portfolio VaR is ~0)" if ratio == float("inf") else f"{ratio:.2f}x"
    callout = (
        f"<div class='callout'><strong>Diversification credit: {html.escape(ratio_text)}</strong>"
        f"<br>The pre-4.6.1 sum-of-absolutes aggregation would report "
        f"<strong>{html.escape(_usd(report.sum_of_absolutes_var_usd))}</strong> for this book; "
        f"the covariance model reports <strong>{html.escape(_usd(p.var_usd))}</strong>. "
        f"The difference is the diversification the old model refused to credit.</div>"
    )

    limit_badge = (
        "<span class='badge bad'>BREACH</span>"
        if report.breaches_var_limit
        else "<span class='badge ok'>within limit</span>"
    )

    component_rows = (
        "".join(
            f"<tr><td>{html.escape(c.symbol)}</td>"
            f"<td class='{_cls(c.notional_usd)}'>{_usd(c.notional_usd)}</td>"
            f"<td>{_usd(c.standalone_var_usd)}</td>"
            f"<td>{c.marginal_var_usd:.6f}</td>"
            f"<td class='{_cls(-c.component_var_usd)}'>{_usd(c.component_var_usd)}</td>"
            f"<td>{c.pct_of_total:.1%}</td></tr>"
            for c in sorted(report.components, key=lambda r: -abs(r.component_var_usd))
        )
        or "<tr><td colspan='6' class='muted'>No positions.</td></tr>"
    )

    scenario_rows = (
        "".join(
            f"<tr><td>{html.escape(s.name)}</td>"
            f"<td class='{_cls(s.pnl_usd)}'>{_usd(s.pnl_usd)}</td>"
            f"<td>{s.pnl_pct_of_nav:.2%}</td>"
            f"<td>{html.escape(s.worst_symbol)}</td>"
            f"<td class='{_cls(s.worst_symbol_pnl_usd)}'>{_usd(s.worst_symbol_pnl_usd)}</td>"
            f"<td>{'<span class="badge bad">BREACH</span>' if s.breaches_limit else ''}</td></tr>"
            for s in report.scenarios
        )
        or "<tr><td colspan='6' class='muted'>No scenarios configured.</td></tr>"
    )

    diag_rows = "".join(
        f"<tr><td>{html.escape(str(k))}</td><td>{html.escape(str(v))}</td></tr>"
        for k, v in sorted(report.diagnostics.items())
    )

    heatmap = _heatmap(report.symbols, correlation) if correlation else ""
    heatmap_section = (
        f"<h2>Correlation matrix</h2><div class='wrap'>{heatmap}</div>" if heatmap else ""
    )

    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MariaAlpha — Portfolio Risk Report</title>
<style>{_CSS}</style></head><body>
<h1>Portfolio Risk Report</h1>
<p class="sub">As of {html.escape(report.as_of.isoformat())} &middot;
NAV {html.escape(_usd(report.nav_usd))} &middot;
{len(report.symbols)} position(s) &middot;
VaR limit {html.escape(_usd(report.var_limit_usd))} {limit_badge}</p>

<div class="tiles">{tile_html}</div>
{callout}

<h2>Component VaR (Euler allocation)</h2>
<p class="sub">Components sum exactly to portfolio VaR. A <span class="pos">negative</span>
component means the position is <em>reducing</em> total risk — a genuine hedge. That is the
information a sum-of-absolutes aggregation cannot express.</p>
<div class="wrap"><table>
<thead><tr><th>Symbol</th><th>Notional</th><th>Standalone VaR</th><th>Marginal VaR</th>
<th>Component VaR</th><th>% of total</th></tr></thead>
<tbody>{component_rows}</tbody></table></div>

<h2>Stress scenarios</h2>
<div class="wrap"><table>
<thead><tr><th>Scenario</th><th>P&amp;L</th><th>% of NAV</th><th>Worst symbol</th>
<th>Worst P&amp;L</th><th></th></tr></thead>
<tbody>{scenario_rows}</tbody></table></div>
{heatmap_section}

<h2>Model diagnostics</h2>
<div class="wrap"><table>
<thead><tr><th>Field</th><th>Value</th></tr></thead>
<tbody>{diag_rows}</tbody></table></div>
</body></html>"""
