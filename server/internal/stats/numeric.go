package stats

import (
	"math"
	"sort"
)

// All arithmetic is on integers so that the Go and Kotlin engines agree exactly
// (docs/STATISTICS.md §2). The only floating-point computation is Spearman's rho.

func intPtr(v int) *int { return &v }

// meanHalfUp is the mean of non-negative values, rounded half up. xs must not be empty.
func meanHalfUp(xs []int) int {
	sum := 0
	for _, x := range xs {
		sum += x
	}
	n := len(xs)
	return (2*sum + n) / (2 * n)
}

// percentile returns the pct-th percentile (0–100) of sorted, using linear interpolation
// between closest ranks ("type 7"), rounded half up. sorted must not be empty.
func percentile(sorted []int, pct int) int {
	pos := (len(sorted) - 1) * pct
	lo, frac := pos/100, pos%100
	if frac == 0 {
		return sorted[lo]
	}
	diff := sorted[lo+1] - sorted[lo]
	return sorted[lo] + (2*diff*frac+100)/200
}

func sortedCopy(xs []int) []int {
	out := append([]int(nil), xs...)
	sort.Ints(out)
	return out
}

func median(xs []int) int { return percentile(sortedCopy(xs), 50) }

// distribution summarises xs, leaving every statistic nil below minSample.
func distribution(xs []int, minSample int) Distribution {
	d := Distribution{SampleSize: len(xs)}
	if len(xs) < minSample || len(xs) == 0 {
		return d
	}
	s := sortedCopy(xs)
	d.Median, d.P25, d.P75 = intPtr(percentile(s, 50)), intPtr(percentile(s, 25)), intPtr(percentile(s, 75))
	d.Min, d.Max, d.Mean = intPtr(s[0]), intPtr(s[len(s)-1]), intPtr(meanHalfUp(s))
	return d
}

// meanValue is the mean of xs, nil below minSample.
func meanValue(xs []int, minSample int) Value {
	v := Value{SampleSize: len(xs)}
	if len(xs) >= minSample && len(xs) > 0 {
		v.Value = intPtr(meanHalfUp(xs))
	}
	return v
}

// ranks assigns 1-based ranks, giving tied values the average of their positions.
func ranks(xs []int) []float64 {
	idx := make([]int, len(xs))
	for i := range idx {
		idx[i] = i
	}
	sort.SliceStable(idx, func(a, b int) bool { return xs[idx[a]] < xs[idx[b]] })
	out := make([]float64, len(xs))
	for i := 0; i < len(idx); {
		j := i
		for j+1 < len(idx) && xs[idx[j+1]] == xs[idx[i]] {
			j++
		}
		rank := float64(i+j)/2 + 1
		for k := i; k <= j; k++ {
			out[idx[k]] = rank
		}
		i = j + 1
	}
	return out
}

// spearmanX1000 is Spearman's rank correlation of the pairs times 1000, rounded half
// away from zero. ok is false when either variable is constant.
func spearmanX1000(xs, ys []int) (value int, ok bool) {
	rx, ry := ranks(xs), ranks(ys)
	n := float64(len(xs))
	var sx, sy float64
	for i := range rx {
		sx += rx[i]
		sy += ry[i]
	}
	mx, my := sx/n, sy/n
	var cov, vx, vy float64
	for i := range rx {
		dx, dy := rx[i]-mx, ry[i]-my
		cov += dx * dy
		vx += dx * dx
		vy += dy * dy
	}
	if vx == 0 || vy == 0 {
		return 0, false
	}
	rho := cov / math.Sqrt(vx*vy)
	if rho < 0 {
		return -int(math.Floor(-rho*1000 + 0.5)), true
	}
	return int(math.Floor(rho*1000 + 0.5)), true
}
