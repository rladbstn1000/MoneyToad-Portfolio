import * as Select from '@radix-ui/react-select';
import { ChevronDown, ChevronUp } from 'lucide-react';
import './JPSelect.css';

export type JPOption = { label: string; value: string };

type JPSelectProps = {
  value: string;
  onChange: (v: string) => void;
  options: JPOption[];
  className?: string;
  placeholder?: string;
  disabled?: boolean;
  colorMap?: Record<string, string>;
};

// JPSelect.tsx 컴포넌트 내부 변경
export default function JPSelect({
  value,
  onChange,
  options,
  className,
  placeholder = '선택',
  disabled,
  colorMap, // ← 추가
}: JPSelectProps) {
  const currentColor = colorMap?.[value];

  return (
    <Select.Root value={value} onValueChange={onChange} disabled={disabled}>
      {/* 트리거 */}
      <Select.Trigger
        aria-label="선택"
        className={`jp-select-trigger ${className || ''}`}
        style={
          currentColor
            ? ({ ['--jp-chip-color' as any]: currentColor } as React.CSSProperties)
            : undefined
        }
      >
        <Select.Value placeholder={placeholder} />
        <Select.Icon>
          <ChevronDown size={16} />
        </Select.Icon>
      </Select.Trigger>

      {/* 드롭다운 */}
      <Select.Portal>
        <Select.Content
          className="jp-select-content"
          align="start"
          sideOffset={6}
          position="popper"
        >
          <Select.ScrollUpButton className="jp-select-scroll-button">
            <ChevronUp size={16} />
          </Select.ScrollUpButton>

          <Select.Viewport className="jp-select-viewport">
            {options.map(opt => {
              const c = colorMap?.[opt.value];
              return (
                <Select.Item
                  key={opt.value}
                  value={opt.value}
                  className="jp-select-item"
                >
                  <Select.ItemText>
                    {/* 왼쪽 색 점 + 라벨 */}
                    <span
                      className="jp-select-dot"
                      style={c ? { background: c } : undefined}
                      aria-hidden
                    />
                    {opt.label}
                  </Select.ItemText>
                </Select.Item>
              );
            })}
          </Select.Viewport>

          <Select.ScrollDownButton className="jp-select-scroll-button">
            <ChevronDown size={16} />
          </Select.ScrollDownButton>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  );
}
