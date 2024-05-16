import { useMemo, ComponentPropsWithoutRef } from "react";
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis } from "recharts";

import { useGetConnectionDataHistory } from "core/api";
import { useConnectionEditService } from "hooks/services/ConnectionEdit/ConnectionEditService";

import { CHART_BASE_HEIGHT, CHART_MAX_HEIGHT, CHART_MIN_HEIGHT, CHART_STREAM_ROW_HEIGHT } from "./constants";
import styles from "./DataMovedGraph.module.scss";
import { BAR_WIDTH, ClickToJob, adjustPositions, tooltipConfig, xAxisConfig } from "../HistoricalOverview/ChartConfig";
import { NoDataMessage } from "../HistoricalOverview/NoDataMessage";
import { UpdateTooltipTickPositions } from "../UptimeStatusGraph/UpdateTooltipTickPositions";
import { UptimeStatusGraphTooltip } from "../UptimeStatusGraph/UptimeStatusGraphTooltip";
import { UptimeDayEntry } from "../UptimeStatusGraph/WaffleChart";

// to manually move the bars we need to intercept the result of Bar::getComposedData and adjust the x coordinates
class PositionedBar extends Bar {
  static getComposedData(values: Parameters<(typeof Bar)["getComposedData"]>[0]) {
    const { xAxis: xAxisDefinition } = values;
    const result = Bar.getComposedData(values);
    if (result.data?.length > 0 && xAxisDefinition) {
      adjustPositions(result.data, xAxisDefinition);
    }
    return result;
  }
}

export const DataMovedGraph: React.FC = () => {
  const { connection } = useConnectionEditService();
  const data = useGetConnectionDataHistory(connection.connectionId);
  const hasData = data.some(({ recordsCommitted }) => recordsCommitted > 0);

  const formattedData = useMemo<UptimeDayEntry[]>(
    () =>
      data.map(({ jobCreatedAt, jobId, jobUpdatedAt, recordsCommitted, recordsEmitted }) => ({
        date: jobCreatedAt * 1000,
        jobId,
        runtimeMs: (jobUpdatedAt - jobCreatedAt) * 1000,
        recordsEmitted,
        recordsCommitted,
        streams: [],
      })),
    [data]
  );

  const chartHeight = Math.max(
    CHART_MIN_HEIGHT,
    Math.min(CHART_MAX_HEIGHT, connection.syncCatalog.streams.length * CHART_STREAM_ROW_HEIGHT + CHART_BASE_HEIGHT)
  );

  if (!hasData) {
    return <NoDataMessage />;
  }

  return (
    <ResponsiveContainer width="100%" height={chartHeight}>
      <BarChart data={formattedData}>
        <UpdateTooltipTickPositions />

        <XAxis dataKey="date" {...xAxisConfig} />

        <PositionedBar
          minPointSize={3} // ensure that the bar is always visible even when the value is 0
          barSize={BAR_WIDTH}
          dataKey="recordsCommitted"
          fill={styles.recordsColor}
          isAnimationActive={false}
        />

        <Tooltip
          cursor={{ fill: styles.chartHoverFill, opacity: 0.65 }}
          wrapperStyle={{ outline: "none", zIndex: styles.tooltipZindex }}
          content={UptimeStatusGraphTooltip}
          {...tooltipConfig}
        />

        {/* last so it draws on top of all other elements and is clickable everywhere */}
        <ClickToJob {...({} as ComponentPropsWithoutRef<typeof ClickToJob>)} />
      </BarChart>
    </ResponsiveContainer>
  );
};
