"""
Unit tests for PerformanceAggregator.
No Kafka, no DB — all DB calls are mocked with unittest.mock.patch.
"""
from decimal import Decimal
from unittest.mock import patch, MagicMock

import pytest

from etl.aggregator import PerformanceAggregator


class TestPerformanceAggregator:

    def test_record_trade_marks_account_dirty(self):
        agg = PerformanceAggregator()
        agg.record_trade(42)
        assert 42 in agg._dirty_accounts

    def test_flush_with_no_dirty_accounts_does_nothing(self):
        agg = PerformanceAggregator()
        # Should not raise even with no dirty accounts
        with patch("etl.aggregator.db.upsert_performance") as mock_upsert:
            agg.flush()
            mock_upsert.assert_not_called()

    def test_flush_upserts_performance_for_dirty_accounts(self):
        agg = PerformanceAggregator()
        agg.record_trade(1)
        agg.record_trade(2)

        with (
            patch("etl.aggregator.db.fetch_account_cash", return_value=Decimal("1000")),
            patch("etl.aggregator.db.fetch_holding_value", return_value=Decimal("500")),
            patch("etl.aggregator.db.upsert_performance") as mock_upsert,
            patch("etl.aggregator._compute_gain", return_value=Decimal("50")),
        ):
            agg.flush()

        assert mock_upsert.call_count == 2
        assert len(agg._dirty_accounts) == 0  # cleared after flush

    def test_flush_skips_account_when_cash_is_none(self):
        agg = PerformanceAggregator()
        agg.record_trade(99)

        with (
            patch("etl.aggregator.db.fetch_account_cash", return_value=None),
            patch("etl.aggregator.db.upsert_performance") as mock_upsert,
        ):
            agg.flush()

        mock_upsert.assert_not_called()

    def test_flush_clears_dirty_set_on_success(self):
        agg = PerformanceAggregator()
        agg.record_trade(7)

        with (
            patch("etl.aggregator.db.fetch_account_cash", return_value=Decimal("200")),
            patch("etl.aggregator.db.fetch_holding_value", return_value=Decimal("100")),
            patch("etl.aggregator.db.upsert_performance"),
            patch("etl.aggregator._compute_gain", return_value=Decimal("0")),
        ):
            agg.flush()

        assert 7 not in agg._dirty_accounts

    def test_duplicate_record_trade_deduped(self):
        agg = PerformanceAggregator()
        agg.record_trade(5)
        agg.record_trade(5)
        agg.record_trade(5)
        assert len(agg._dirty_accounts) == 1
