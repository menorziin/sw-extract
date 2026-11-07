#pragma once

// Keeps the emulation threads (PPC cores, Latte GPU thread) off the efficiency cluster on
// big.LITTLE phones. The scheduler otherwise moves them to the small cores during short idle
// moments, and the next burst of work starts there, which shows up as frame time spikes.

#if defined(__ANDROID__)
#include <sched.h>
#include <unistd.h>
#include <algorithm>
#include <fstream>
#include <string>
#include <vector>

namespace PerformanceCores
{
	// Every core except the lowest-frequency cluster; empty when all cores are alike.
	inline const std::vector<int>& List()
	{
		static const std::vector<int> cores = [] {
			std::vector<std::pair<int, long>> maxFreq;
			const long count = sysconf(_SC_NPROCESSORS_CONF);
			for (int cpu = 0; cpu < count; cpu++)
			{
				std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
				long khz = 0;
				if (f >> khz && khz > 0)
					maxFreq.emplace_back(cpu, khz);
			}
			std::vector<int> result;
			if (maxFreq.size() < 2)
				return result;
			auto [lo, hi] = std::minmax_element(maxFreq.begin(), maxFreq.end(),
												[](const auto& a, const auto& b) { return a.second < b.second; });
			const long slowest = lo->second;
			if (slowest == hi->second)
				return result;
			for (const auto& [cpu, khz] : maxFreq)
				if (khz > slowest)
					result.push_back(cpu);
			return result;
		}();
		return cores;
	}

	// Restricts the calling thread to the performance cores. Returns false if nothing changed.
	inline bool PinCurrentThread()
	{
		const auto& cores = List();
		if (cores.empty())
			return false;
		cpu_set_t set;
		CPU_ZERO(&set);
		for (int cpu : cores)
			CPU_SET(cpu, &set);
		return sched_setaffinity(0, sizeof(set), &set) == 0;
	}
} // namespace PerformanceCores
#else
namespace PerformanceCores
{
	inline bool PinCurrentThread() { return false; }
} // namespace PerformanceCores
#endif
