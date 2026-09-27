import { describe, expect, it } from 'vitest';

import { Payment } from '../../core/api/models';
import { defaultMethod, newRefund, refundValid, refundable } from './refund-fields';

const payment = (over: Partial<Payment> = {}): Payment => ({
  id: 'p1',
  purpose: 'BOOKING',
  method: 'ONLINE',
  provider: 'razorpay',
  amount: 1000,
  status: 'SUCCEEDED',
  failureReason: null,
  createdAt: '2026-09-01T10:00:00Z',
  refundedAmount: 0,
  ...over,
});

describe('refund rules', () => {
  it('only offers what has not been refunded yet', () => {
    expect(refundable(payment({ refundedAmount: 250.5 }))).toBe(749.5);
    expect(refundable(payment({ refundedAmount: 1000 }))).toBe(0);
  });

  it('sends online payments back through the gateway and cash manually', () => {
    expect(defaultMethod(payment())).toBe('GATEWAY');
    expect(defaultMethod(payment({ method: 'CASH', provider: null }))).toBe('MANUAL');
  });

  it('starts with a full refund charged to the Genie', () => {
    expect(newRefund(payment({ refundedAmount: 400 }))).toMatchObject({ amount: 600, liability: 'GENIE', method: 'GATEWAY' });
  });

  it('rejects amounts above what is left', () => {
    const p = payment({ refundedAmount: 400 });
    expect(refundValid({ ...newRefund(p), amount: 600 }, p)).toBe(true);
    expect(refundValid({ ...newRefund(p), amount: 601 }, p)).toBe(false);
    expect(refundValid({ ...newRefund(p), amount: 0 }, p)).toBe(false);
  });

  it('needs a Genie share within the amount when split', () => {
    const p = payment();
    expect(refundValid({ ...newRefund(p), liability: 'SPLIT', genieShare: null }, p)).toBe(false);
    expect(refundValid({ ...newRefund(p), liability: 'SPLIT', genieShare: 1200 }, p)).toBe(false);
    expect(refundValid({ ...newRefund(p), liability: 'SPLIT', genieShare: 300 }, p)).toBe(true);
  });

  it('needs a transfer reference for manual refunds', () => {
    const p = payment({ method: 'CASH' });
    expect(refundValid(newRefund(p), p)).toBe(false);
    expect(refundValid({ ...newRefund(p), manualReference: 'UPI 1234' }, p)).toBe(true);
  });
});
