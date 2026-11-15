import { useEffect, useRef, useState } from "react";
import { Button, Modal } from "./FormElements";

const steps = ["Choose a year range", "Choose a year", "Choose a month", "Choose a day"];
const firstBirthYear = 1927;
const lastBirthYear = 2016;
const yearsPerGroup = 10;
const rangeEnds = Array.from(
  { length: Math.floor((lastBirthYear - firstBirthYear) / yearsPerGroup) + 1 },
  (_, index) => lastBirthYear - index * yearsPerGroup,
);

function formatDate(year: number, month: number, day: number) {
  return [String(year).padStart(4, "0"), String(month).padStart(2, "0"), String(day).padStart(2, "0")].join("-");
}

function monthName(year: number, month: number) {
  return new Date(year, month - 1, 1).toLocaleDateString(undefined, { month: "long" });
}

function numbers(min: number, max: number) {
  return Array.from({ length: max - min + 1 }, (_, index) => min + index);
}

export function BirthdayField({ defaultValue = "", required = false }: {
  defaultValue?: string;
  required?: boolean;
}) {
  const today = new Date();
  const currentYear = today.getFullYear();
  const currentMonth = today.getMonth() + 1;
  const earliestYear = currentYear - 130;
  const earliestDay = Math.min(today.getDate(), new Date(earliestYear, currentMonth, 0).getDate());
  const earliest = formatDate(earliestYear, currentMonth, earliestDay);
  const latest = formatDate(currentYear, currentMonth, today.getDate());
  const [value, setValue] = useState(() => {
    const [y, m, d] = defaultValue.split("-").map(Number);
    const date = new Date(y, m - 1, d);
    return defaultValue >= earliest && defaultValue <= latest &&
      date.getFullYear() === y && date.getMonth() === m - 1 && date.getDate() === d ? defaultValue : "";
  });
  const [open, setOpen] = useState(false);
  const [step, setStep] = useState(0);
  const [rangeEnd, setRangeEnd] = useState(lastBirthYear);
  const [year, setYear] = useState(lastBirthYear);
  const [month, setMonth] = useState(1);
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (open) heading.current?.focus();
  }, [open, step]);

  const [savedYear, savedMonth, savedDay] = value.split("-").map(Number);
  const displayDate = value && new Date(savedYear, savedMonth - 1, savedDay)
    .toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
  const options = [
    () => rangeEnds.map((end) => {
      const start = Math.max(end - yearsPerGroup + 1, firstBirthYear);
      return [end, `${end}–${start}`] as const;
    }),
    () => numbers(Math.max(rangeEnd - yearsPerGroup + 1, firstBirthYear), rangeEnd).reverse()
      .map((number) => [number, String(number)] as const),
    () => numbers(1, 12).map((number) => [number, monthName(year, number)] as const),
    () => numbers(1, new Date(year, month, 0).getDate()).map((number) => [number, String(number)] as const),
  ];

  function openPicker() {
    setStep(0);
    setOpen(true);
  }

  function choose(number: number) {
    switch (step) {
      case 0: setRangeEnd(number); break;
      case 1: setYear(number); break;
      case 2: setMonth(number); break;
      case 3:
        setValue(formatDate(year, month, number));
        setOpen(false);
        return;
    }
    setStep(step + 1);
  }

  return (
    <fieldset className="birthday-field">
      <legend>Date of birth{required ? " (required)" : " (optional)"}</legend>
      <Button variant="outline" onClick={openPicker} aria-haspopup="dialog">
        {displayDate || "Choose date of birth"}
      </Button>
      {!required && value && (
        <Button variant="outline" onClick={() => setValue("")}>Clear date of birth</Button>
      )}
      <input type="hidden" name="dob" value={value} />
      {open && (
        <Modal title="Choose date of birth" onClose={() => setOpen(false)}>
          <h3 ref={heading} tabIndex={-1}>{steps[step]}</h3>
          {step === 2 && <p>{year}</p>}
          {step === 3 && <p>{monthName(year, month)} {year}</p>}
          <div className="birthday-options">
            {options[step]().map(([number, label]) => (
              <Button key={number} variant="outline" onClick={() => choose(number)}>{label}</Button>
            ))}
          </div>
          {step > 0 && <Button variant="outline" onClick={() => setStep(step - 1)}>Back</Button>}
        </Modal>
      )}
    </fieldset>
  );
}
