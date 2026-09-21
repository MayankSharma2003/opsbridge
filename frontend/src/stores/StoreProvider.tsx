import { useState, type ReactNode } from 'react';
import { RootStore } from './RootStore';
import { StoreContext } from './StoreContext';

export function StoreProvider({ children }: { children: ReactNode }) {
  const [store] = useState(() => new RootStore());
  return <StoreContext.Provider value={store}>{children}</StoreContext.Provider>;
}
