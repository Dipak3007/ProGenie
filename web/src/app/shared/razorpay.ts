/** Minimal typing for Razorpay Checkout (https://checkout.razorpay.com/v1/checkout.js). */
export interface RazorpaySuccess {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

export interface RazorpayOptions {
  key: string;
  order_id: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
  prefill?: { name?: string; email?: string; contact?: string };
  theme?: { color: string };
  handler: (response: RazorpaySuccess) => void;
  modal?: { ondismiss?: () => void };
}

interface RazorpayInstance {
  open(): void;
  on(event: 'payment.failed', cb: (res: { error: { description?: string; reason?: string } }) => void): void;
}

type RazorpayCtor = new (options: RazorpayOptions) => RazorpayInstance;

const SRC = 'https://checkout.razorpay.com/v1/checkout.js';
let loading: Promise<RazorpayCtor> | null = null;

/** Loads checkout.js once, only when someone actually pays online (the Nginx CSP allows this host). */
export function loadRazorpay(): Promise<RazorpayCtor> {
  const existing = (window as unknown as { Razorpay?: RazorpayCtor }).Razorpay;
  if (existing) return Promise.resolve(existing);
  loading ??= new Promise<RazorpayCtor>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = SRC;
    script.async = true;
    script.onload = () => {
      const ctor = (window as unknown as { Razorpay?: RazorpayCtor }).Razorpay;
      if (ctor) {
        resolve(ctor);
      } else {
        reject(new Error('Razorpay did not load'));
      }
    };
    script.onerror = () => {
      loading = null;
      reject(new Error('Could not load the Razorpay checkout. Check your connection.'));
    };
    document.head.appendChild(script);
  });
  return loading;
}
